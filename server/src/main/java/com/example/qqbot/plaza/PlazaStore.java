package com.example.qqbot.plaza;

import com.example.qqbot.config.PlazaProperties;
import com.example.qqbot.persistence.PlazaRepository;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 问答广场的数据层：投票 + 求助记录。
 *
 * <p>复用**共用**的 SQLite 连接（和 CommandStore 一样）——
 * 多个连接同时开同一个 SQLite 文件会触发 WAL 共享内存冲突。
 *
 * <p>⚠️ 这里原先有 {@code @DependsOn("qaStore")} 来保证"连上库"先于建表。
 * 连接所有权移交到 {@code persistence.SqliteDatabase} 之后**它不再需要了** ——
 * 构造器注入本身就保证了依赖先初始化完（含 {@code @PostConstruct}）。
 * 留着它才是风险：字符串形式的运行期耦合，改个类名就静默失效。
 *
 * <h2>本类只剩「语义」</h2>
 * SQL 与 {@code java.sql} 全部搬进了 {@link PlazaRepository}。这里管的是：
 * 投票者哈希（隐私）、票型校验、短码生成与剥离、限流口径、以及后台 JSON 的形状。
 * 「跨表报表式读取」（热词榜、已投票答案）在 {@code persistence.PlazaQueryRepository}。
 */
@Component
public class PlazaStore {

    private static final Logger log = LoggerFactory.getLogger(PlazaStore.class);

    /** 数据访问全部委托给它 —— SQL 与 java.sql 都在 persistence */
    private final PlazaRepository repo;

    private final PlazaProperties plazaProps;

    private volatile boolean available;

    public PlazaStore(PlazaRepository repo, PlazaProperties plazaProps) {
        this.repo = repo;
        this.plazaProps = plazaProps;
    }

    public boolean isAvailable() {
        return available;
    }

    @PostConstruct
    void init() {
        try {
            if (!repo.isAvailable()) {
                log.warn("[PLAZA] 问答库不可用，广场功能一并关闭");
                return;
            }
            repo.initSchema();
            available = true;
            log.info("[PLAZA] 广场就绪：投票 {} 条，求助 {} 条", voteCount(), helpCount());
        } catch (Exception e) {
            log.warn("[PLAZA] 初始化失败（不影响正常问答）：{}", e.getMessage());
            available = false;
        }
    }

    // ==================== 投票者哈希 ====================

    /**
     * 把 QQ 号转成投票者标识。
     *
     * <p>为什么哈希：公开站可能被爬，投票记录不该关联到具体的人。
     * 但同一个 QQ 号每次得到同样的 hash —— 所以能防重复投票。
     *
     * <p>注意：这个 hash 不是匿名（能访问服务器的人可以枚举 QQ 号比对），
     * 但它防的是「公开站被爬」这个场景。
     */
    public String voterHash(long qq) {
        String salt = plazaProps.getVoteSalt();
        if (salt == null || salt.isBlank()) {
            salt = "qqbot-plaza-default-salt";
        }
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] h = md.digest((qq + ":" + salt).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(h, 0, 16);
        } catch (Exception e) {
            return String.valueOf(qq);
        }
    }

    // ==================== 投票 ====================

    /** 一条回答的投票统计 */
    public record VoteSummary(long statId, long up, long down, long outdated) {

        public long score() {
            return up - down;
        }

        /** 是否「全被踩」——踩数超过赞数且达到阈值 */
        public boolean isBadlyDownvoted(int threshold) {
            return down >= threshold && down > up;
        }
    }

    /**
     * 投票（幂等）。
     *
     * <p>同一人同一答案重复投 = 改票（UNIQUE + UPSERT），不是新增。
     *
     * @param vote up / down / outdated
     */
    public boolean vote(long statId, long voterQq, String vote) {
        if (!available || !isValidVote(vote)) {
            return false;
        }
        try {
            repo.vote(statId, voterHash(voterQq), vote, Instant.now().toString());
            return true;
        } catch (Exception e) {
            log.warn("[PLAZA] 投票失败：{}", e.getMessage());
            return false;
        }
    }

    /** 查看某人投过什么（前端高亮已投） */
    public String myVote(long statId, long voterQq) {
        if (!available) {
            return null;
        }
        try {
            return repo.findVote(statId, voterHash(voterQq));
        } catch (Exception e) {
            return null;
        }
    }

    /** 批量取投票统计（避免 N+1 查询）—— 只含**有投票的**那些 stat_id */
    public Map<Long, VoteSummary> voteSummaries(List<Long> statIds) {
        Map<Long, VoteSummary> out = new LinkedHashMap<>();
        if (!available || statIds == null || statIds.isEmpty()) {
            return out;
        }
        try {
            for (PlazaRepository.Tally t : repo.voteTallies(statIds)) {
                out.put(t.statId(), new VoteSummary(t.statId(), t.up(), t.down(), t.outdated()));
            }
        } catch (Exception e) {
            log.warn("[PLAZA] 取投票统计失败：{}", e.getMessage());
        }
        return out;
    }

    /** 投票明细（管理后台） */
    public List<Map<String, Object>> voteDetails(long statId) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (!available) {
            return out;
        }
        try {
            for (PlazaRepository.VoteDetail d : repo.voteDetails(statId)) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("voterHash", d.voterHash());
                m.put("vote", d.vote());
                m.put("createdAt", d.createdAt());
                m.put("updatedAt", d.updatedAt());
                out.add(m);
            }
        } catch (Exception e) {
            log.warn("[PLAZA] 取投票明细失败：{}", e.getMessage());
        }
        return out;
    }

    public static boolean isValidVote(String vote) {
        return "up".equals(vote) || "down".equals(vote) || "outdated".equals(vote);
    }

    public long voteCount() {
        if (!available) {
            return 0;
        }
        try {
            return repo.voteCount();
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * 下架一条内容。
     *
     * <p>做法不是删除，而是清空投票 —— 清空后它就达不到「被点赞」门槛，
     * 自然从公开站消失。这样原文仍保留便于复查，误操作了重新点赞也能恢复。
     */
    public boolean takedown(long statId) {
        if (!available) {
            return false;
        }
        try {
            int n = repo.takedown(statId);
            log.info("[PLAZA] 已下架 statId={}，清空 {} 条投票", statId, n);
            return true;
        } catch (Exception e) {
            log.warn("[PLAZA] 下架失败：{}", e.getMessage());
            return false;
        }
    }

    // ==================== 求助记录 ====================

    /**
     * 创建一条「待确认」的求助。
     *
     * <p>为什么要有这个中间态：网页访客无法证明自己属于某个群，
     * 所以网页只能"申请"，真正发出去要等群内确认（带真实群号）。
     *
     * <p>返回一个短码，用户把它发到群里就能触发。
     */
    public String createHelpRequest(String keyword, String question) {
        if (!available) {
            return null;
        }
        String code = Long.toHexString(System.nanoTime()).substring(0, 6);
        try {
            repo.insertPendingHelp(Instant.now().toString(), keyword, code + "|" + question);
            return code;
        } catch (Exception e) {
            log.warn("[PLAZA] 创建求助失败：{}", e.getMessage());
            return null;
        }
    }

    /** 按短码找出待确认的求助 */
    public Map<String, Object> findPendingHelp(String code) {
        if (!available || code == null) {
            return null;
        }
        try {
            PlazaRepository.PendingHelp p = repo.findPendingHelp(code + "|%");
            if (p == null) {
                return null;
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("id", p.id());
            out.put("keyword", p.keyword());
            // 库里存的是「短码|问题」，剥掉前缀才是给人看的
            String q = p.question();
            out.put("question", q != null && q.contains("|")
                    ? q.substring(q.indexOf('|') + 1) : q);
            return out;
        } catch (Exception e) {
            log.warn("[PLAZA] 查求助失败：{}", e.getMessage());
            return null;
        }
    }

    /** 确认发出（补上真实群号和用户） */
    public void confirmHelp(long id, long groupId, long userId) {
        if (!available) {
            return;
        }
        try {
            repo.confirmHelp(id, groupId, userId);
        } catch (Exception e) {
            log.warn("[PLAZA] 确认求助失败：{}", e.getMessage());
        }
    }

    public void logHelp(long groupId, long userId, String keyword, String question) {
        if (!available) {
            return;
        }
        try {
            repo.logHelp(Instant.now().toString(), keyword, question, groupId, userId);
        } catch (Exception e) {
            log.warn("[PLAZA] 记录求助失败：{}", e.getMessage());
        }
    }

    /** 某关键词在某群今天求助过几次（限流用） */
    public long helpCountToday(String keyword, long groupId) {
        return scalar(() -> repo.helpCountToday(keyword, groupId));
    }

    /** 某人今天求助过几次 */
    public long helpCountTodayByUser(long userId) {
        return scalar(() -> repo.helpCountTodayByUser(userId));
    }

    /** 某群最近一小时有几条求助（防刷屏） */
    public long helpCountLastHour(long groupId) {
        return scalar(() -> repo.helpCountLastHour(groupId));
    }

    public long helpCount() {
        return scalar(repo::helpCount);
    }

    /**
     * 计数类查询的统一降级：不可用或出错都当 0。
     *
     * <p>这几个计数全是**限流**用的（"今天问过几次"）。抛异常会让求助功能整个不可用，
     * 而返回 0 的后果只是"限流没生效" —— 两害相权取其轻。
     */
    private long scalar(java.util.function.LongSupplier source) {
        if (!available) {
            return 0;
        }
        try {
            return source.getAsLong();
        } catch (Exception e) {
            log.debug("[PLAZA] 查询失败：{}", e.getMessage());
            return 0;
        }
    }

    /** 最近的求助记录（管理后台） */
    public List<Map<String, Object>> recentHelp(int limit) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (!available) {
            return out;
        }
        try {
            for (PlazaRepository.HelpRow h : repo.recentHelp(limit)) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("ts", h.ts());
                m.put("keyword", h.keyword());
                m.put("question", h.question());
                m.put("groupId", h.groupId());
                m.put("userId", h.userId());
                m.put("status", h.status());
                out.add(m);
            }
        } catch (Exception e) {
            log.warn("[PLAZA] 取求助记录失败：{}", e.getMessage());
        }
        return out;
    }

    /**
     * 保存广场生成的新答案。
     *
     * <p>写三处（{@code qa_stat} + {@code qa_raw} + {@code qa_keyword}），
     * 由 {@link PlazaRepository#saveGeneratedAnswer} 保证原子 ——
     * 原来没有事务，第二步失败就留下一条**没有原文的统计行**。
     *
     * <p>⚠️ {@code group_id} / {@code user_id} 都给 0 —— 这条不是群聊产生的，不该伪造归属。
     */
    public long saveGeneratedAnswer(String keyword, String question, String answer) throws Exception {
        if (!available) {
            throw new IllegalStateException("广场数据库不可用");
        }
        return repo.saveGeneratedAnswer(Instant.now().toString(), question, answer, keyword);
    }
}
