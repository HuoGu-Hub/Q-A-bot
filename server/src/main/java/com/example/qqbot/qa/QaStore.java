package com.example.qqbot.qa;

import com.example.qqbot.config.QaProperties;
import com.example.qqbot.persistence.QaStoreRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * 问答记录的 SQLite 存储。
 *
 * <p><b>两层结构</b>（见设计文档第四节）：
 * <pre>
 *   qa_stat     派生层，永久保留 —— 时间/群/用户/检索指标/耗时/标注
 *   qa_raw      原始层，到期删除 —— 问题原文/引用原文/回答原文
 *   qa_keyword  派生层，永久保留 —— 从问题里抽出的游戏名词
 * </pre>
 *
 * <p>删原文不需要先做聚合：统计需要的字段本来就在 qa_stat 里。
 *
 * <p><b>本类绝不让机器人挂掉</b>：初始化失败就置为不可用，
 * 之后所有写入变成空操作，只记一条警告。
 *
 * <h2>本类只剩「语义」</h2>
 * SQL 与 {@code java.sql} 全部搬进了 {@link QaStoreRepository}。这里管的是：
 * {@link QaRecord} 怎么摊成三层行形状、保留天数的判定、UA 截断、以及降级策略。
 *
 * <p>⚠️ 它**不再是连接提供者**。原先它 {@code implements SqliteConnectionProvider}
 * 只是一个过渡：连接所有权移交给 {@code persistence.SqliteDatabase} 之后，
 * 那个转发实现和"自己开库"的测试构造器都已经删掉 —— 后者是生产代码里的测试后门，
 * 而且 {@code connection()} 的返回类型就是 {@code java.sql.Connection}，
 * 留着它「持久化层之外不得出现 java.sql」这条护栏就永远转不了正。
 */
@Component
public class QaStore {

    private static final Logger log = LoggerFactory.getLogger(QaStore.class);

    /** UA 入库前截断到多少字（防一条超长 UA 撑爆表） */
    private static final int MAX_UA_LEN = 200;

    /** 数据访问全部委托给它 —— SQL 与 java.sql 都在 persistence */
    private final QaStoreRepository repo;
    private final QaProperties props;
    private final ObjectMapper mapper;

    private volatile boolean available;

    public QaStore(QaStoreRepository repo, QaProperties props, ObjectMapper mapper) {
        this.repo = repo;
        this.props = props;
        this.mapper = mapper;
    }

    /** 只反映"问答表能不能用"；连接本身的开关在 {@code SqliteDatabase} */
    public boolean isAvailable() {
        return available && repo.isAvailable();
    }

    /** 初始化（public 以便测试显式调用） */
    @PostConstruct
    public void init() {
        if (!repo.isAvailable()) {
            log.warn("[QA] 数据库不可用，本次运行将不记录问答（不影响正常回答）");
            return;
        }
        try {
            repo.initSchema();
            available = true;
            log.info("[QA] 问答记录就绪（原文保留 {} 天，0=永不删）", props.getRetentionDays());
        } catch (Exception e) {
            log.warn("[QA] 初始化记录表失败，本次运行将不记录问答（不影响正常回答）：{}", e.getMessage());
            available = false;
        }
    }

    /**
     * 只关"记录功能"，**不关连接** —— 连接归 {@code SqliteDatabase} 所有，
     * 由它的 {@code @PreDestroy} 统一关。这里要是还关一次，
     * 会把别的模块正在用的共用连接一起关掉。
     */
    public void close() {
        available = false;
    }

    /** 批量落盘，一个事务。调用方是单线程的 {@link QaRecorder} */
    public void insertBatch(List<QaRecord> records) {
        if (!available || records == null || records.isEmpty()) {
            return;
        }
        try {
            List<QaStoreRepository.NewRecord> rows = new ArrayList<>(records.size());
            for (QaRecord r : records) {
                rows.add(toNewRecord(r));
            }
            repo.insertBatch(rows);
        } catch (Exception e) {
            log.warn("[QA] 写入记录失败（丢弃本批 {} 条，不影响回答）：{}", records.size(), e.getMessage());
        }
    }

    /**
     * 把一条问答摊成三层行形状。
     *
     * <p>{@code answer_len} / {@code answer_empty} 在这里算，而不是在 SQL 里 ——
     * 它们是"答案长什么样"的业务口径，持久层只该收到两个已经定好的值。
     */
    private static QaStoreRepository.NewRecord toNewRecord(QaRecord r) {
        QaStoreRepository.StatRow stat = new QaStoreRepository.StatRow(
                r.ts(), r.groupId(), r.userId(), r.messageId(), r.imageCount(),
                r.kbEnabled(), r.hitCount(), r.topScore(), r.bestCosine(), r.bestCosineRaw(),
                r.sources(), r.retrievedJson(),
                r.answer() == null ? 0 : r.answer().length(),
                r.answer() == null || r.answer().isBlank(),
                r.guardAction(), r.retrieveMs(), r.llmMs(), r.totalMs(), r.model());
        QaStoreRepository.RawRow raw = new QaStoreRepository.RawRow(
                r.question(), r.quoteText(), r.answer());
        List<QaStoreRepository.KeywordRow> keywords = new ArrayList<>();
        for (QaRecord.Keyword k : r.keywords()) {
            keywords.add(new QaStoreRepository.KeywordRow(k.zh(), k.en(), k.inKb()));
        }
        return new QaStoreRepository.NewRecord(stat, raw, keywords);
    }

    /**
     * 删除过期的原文。**只删 qa_raw，qa_stat 和 qa_keyword 一行不动。**
     *
     * @param retentionDays 保留天数；{@code <= 0} 表示永不删除
     * @param dryRun        true 时只统计不删除
     * @return 受影响（或将要影响）的行数
     */
    public int purgeRaw(int retentionDays, boolean dryRun) {
        if (!available || retentionDays <= 0) {
            return 0;
        }
        String cutoff = Instant.now().minus(retentionDays, ChronoUnit.DAYS).toString();
        try {
            long count = repo.countRawOlderThan(cutoff);
            if (dryRun || count == 0) {
                return (int) count;
            }
            int deleted = repo.deleteRawOlderThan(cutoff);
            log.info("[QA] 已清理 {} 条过期原文（截止 {}，保留 {} 天）；统计表未受影响",
                    deleted, cutoff, retentionDays);
            return deleted;
        } catch (Exception e) {
            log.warn("[QA] 清理过期原文失败：{}", e.getMessage());
            return 0;
        }
    }

    /**
     * 给一条记录打标。
     *
     * <p>这是 S4 闭环的起点：只有知道"哪条答错了"，才能去补术语或调阈值。
     *
     * @param verdict good / bad / no_source / hallucination / unknown
     */
    public boolean annotate(long id, String verdict, String note, String by) {
        if (!available) {
            return false;
        }
        try {
            return repo.annotate(id, verdict, note, by, Instant.now().toString());
        } catch (Exception e) {
            log.warn("[QA] 标注失败：{}", e.getMessage());
            return false;
        }
    }

    /**
     * 标记"这条是追问"（同一个人短时间内又问了）。
     *
     * <p>追问是**零成本的准确率信号**：上一条要是答好了，通常不会马上再问一次。
     * 由记录时自动判定，不需要人工介入。
     *
     * <p>只作用于还没被人工标过的行（SQL 里的 {@code verdict IS NULL OR verdict = 'unknown'}）——
     * 自动标记**绝不能覆盖人工结论**。
     */
    public boolean markFollowUp(long id) {
        if (!available) {
            return false;
        }
        try {
            return repo.markFollowUp(id, Instant.now().toString());
        } catch (Exception e) {
            log.debug("[QA] 标记追问失败：{}", e.getMessage());
            return false;
        }
    }

    /** 记一次后台访问（IP 已哈希） */
    public void recordVisit(String path, String ipHash, String userAgent, String action) {
        if (!available) {
            return;
        }
        String ua = userAgent == null ? "" : userAgent;
        if (ua.length() > MAX_UA_LEN) {
            ua = ua.substring(0, MAX_UA_LEN);
        }
        try {
            repo.recordVisit(Instant.now().toString(), path, ipHash, ua, action);
        } catch (Exception e) {
            log.debug("[QA] 记录后台访问失败：{}", e.getMessage());
        }
    }

    /** 统计表行数（自检用） */
    public long countStat() {
        if (!available) {
            return 0;
        }
        try {
            return repo.countStat();
        } catch (Exception e) {
            return 0;
        }
    }

    /** 原文表行数（自检用） */
    public long countRaw() {
        if (!available) {
            return 0;
        }
        try {
            return repo.countRaw();
        } catch (Exception e) {
            return 0;
        }
    }

    public ObjectMapper mapper() {
        return mapper;
    }
}
