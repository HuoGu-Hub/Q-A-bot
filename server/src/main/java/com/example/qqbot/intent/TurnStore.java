package com.example.qqbot.intent;

import com.example.qqbot.persistence.TurnRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * 最近几轮问答的读写 —— 「上一轮到底聊了什么」的唯一入口。
 *
 * <p>存在的理由只有一个：群里 40% 的提问是三分钟内的追问（"那个怎么获得""不是 185 吗"），
 * 而每个请求原本都是独立的。没有这段记忆，纠正就只能当成新问题重答一遍。
 *
 * <h2>三条约束</h2>
 * <ul>
 *   <li><b>只存摘要</b>：{@code answer_digest} 取回答前 60 字，不额外调模型；</li>
 *   <li><b>只留几条</b>：每次写入顺手裁掉更早的，记忆不是档案；</li>
 *   <li><b>出错就当没有</b>：读写失败一律返回空，绝不能让"记不住"变成"答不了"。</li>
 * </ul>
 */
@Component
public class TurnStore {

    private static final Logger log = LoggerFactory.getLogger(TurnStore.class);

    /** 摘要长度：够模型认出"上次说的是哪个东西"，又不至于把一轮回答整个搬过来 */
    private static final int DIGEST_CHARS = 60;

    /** 记忆保留天数：超过就整条删掉，避免长期没人问的群无限增长 */
    private static final int KEEP_DAYS = 7;

    /** 一轮的摘要 */
    public record Turn(long id, String question, String answerDigest) {
    }

    private final TurnRepository repo;
    private final IntentPolicy policy;

    public TurnStore(TurnRepository repo, IntentPolicy policy) {
        this.repo = repo;
        this.policy = policy;
        if (repo.isAvailable()) {
            repo.initSchema();
        }
    }

    /** 这个群这个人最近的几轮，新的在前。取不到就返回空表 */
    public List<Turn> recent(long groupId, long userId) {
        if (!repo.isAvailable()) {
            return List.of();
        }
        try {
            int minutes = Math.max(1, policy.getRecentMinutes());
            int limit = Math.max(1, policy.getRecentTurns());
            String since = Instant.now().minus(minutes, ChronoUnit.MINUTES).toString();
            List<Turn> turns = new ArrayList<>();
            for (TurnRepository.Row row : repo.recent(groupId, userId, since, limit)) {
                turns.add(new Turn(row.id(), row.question(), row.answerDigest()));
            }
            return turns;
        } catch (Exception e) {
            log.warn("[TURN] 读最近几轮失败（当作没有）：{}", e.getMessage());
            return List.of();
        }
    }

    /** 记一轮。失败只记日志 */
    public void append(long groupId, long userId, String question, String answer) {
        if (!repo.isAvailable() || question == null || question.isBlank()) {
            return;
        }
        try {
            repo.insert(groupId, userId, Instant.now().toString(), question.strip(), digest(answer));
            repo.trim(groupId, userId, Math.max(1, policy.getRecentTurns()));
        } catch (Exception e) {
            log.warn("[TURN] 记一轮失败（不影响回答）：{}", e.getMessage());
        }
    }

    /**
     * 把"最近这一轮"标成被纠正过。
     *
     * <p>必须在 {@link #append} **之前**调用：先标旧的，再记新的，否则标到的是自己。
     * 这个标记不做任何决策，只是让"纠正率"事后可统计。
     */
    public void markCorrected(long groupId, long userId) {
        if (!repo.isAvailable()) {
            return;
        }
        try {
            List<TurnRepository.Row> rows = repo.recent(groupId, userId,
                    Instant.now().minus(KEEP_DAYS, ChronoUnit.DAYS).toString(), 1);
            if (!rows.isEmpty()) {
                repo.markCorrected(rows.get(0).id());
            }
        } catch (Exception e) {
            log.warn("[TURN] 标记纠正失败（不影响回答）：{}", e.getMessage());
        }
    }

    /** 每天清一次过期记忆 */
    @Scheduled(cron = "${app.intent.cleanup-cron:0 40 4 * * *}")
    public void scheduledCleanup() {
        if (!repo.isAvailable()) {
            return;
        }
        try {
            int removed = repo.deleteOlderThan(
                    Instant.now().minus(KEEP_DAYS, ChronoUnit.DAYS).toString());
            if (removed > 0) {
                log.info("[TURN] 清理过期记忆 {} 条", removed);
            }
        } catch (Exception e) {
            log.warn("[TURN] 清理记忆失败：{}", e.getMessage());
        }
    }

    /** 摘要：去换行，截断。没有回答就存空串 */
    static String digest(String answer) {
        if (answer == null || answer.isBlank()) {
            return "";
        }
        String flat = answer.replaceAll("\\s+", " ").strip();
        return flat.length() <= DIGEST_CHARS ? flat : flat.substring(0, DIGEST_CHARS) + "…";
    }
}
