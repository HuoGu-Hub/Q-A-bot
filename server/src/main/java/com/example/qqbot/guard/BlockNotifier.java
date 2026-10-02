package com.example.qqbot.guard;

import com.example.qqbot.config.GuardProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 「被拦截了，要不要告诉用户一声」的统一闸门。
 *
 * <p>为什么需要它：提示话术本身也会产生消息。2000 人的群里 50 个人同时被拦，
 * 如果每人都回一句提示，就等于**用提示把群刷爆了**。
 *
 * <p>两层保护：
 * <ol>
 *   <li><b>每用户冷却</b>（默认 300 秒）—— 同一个人不会反复被提示</li>
 *   <li><b>每群每分钟上限</b>（默认 3 条）—— 整个群的提示量有天花板</li>
 * </ol>
 *
 * <p>限流提示、排队超时提示都走这里，共用同一套配额。
 */
@Component
public class BlockNotifier {

    private static final Logger log = LoggerFactory.getLogger(BlockNotifier.class);

    private static final long ONE_MINUTE = 60_000L;

    private final GuardProperties.RateLimit config;
    private final RateLimiter limiter;

    /** 用户 → 上次提示时间 */
    private final Map<Long, Long> lastNotifyAt = new ConcurrentHashMap<>();

    public BlockNotifier(GuardProperties properties, RateLimiter limiter) {
        this.config = properties.getRateLimit();
        this.limiter = limiter;
    }

    /**
     * @return true 表示这次可以发提示
     */
    public boolean allow(Long userId, Long groupId) {
        long now = System.currentTimeMillis();

        if (userId != null) {
            long cooldown = config.getNotifyCooldownSeconds() * 1000L;
            Long last = lastNotifyAt.get(userId);
            if (last != null && now - last < cooldown) {
                return false;
            }
        }

        // 用独立的 key 空间，不占用正常回复的配额
        if (groupId != null && config.getNotifyGroupPerMinute() > 0) {
            boolean ok = limiter.tryAcquireAll(List.of(new RateLimiter.Bucket(
                    "notify:group:" + groupId, ONE_MINUTE, config.getNotifyGroupPerMinute())));
            if (!ok) {
                log.debug("[GUARD] 群 {} 的提示已达每分钟上限（{} 条），本次不再提示",
                        groupId, config.getNotifyGroupPerMinute());
                return false;
            }
        }

        if (userId != null) {
            lastNotifyAt.put(userId, now);
            prune(now);
        }
        return true;
    }

    private void prune(long now) {
        if (lastNotifyAt.size() < 2000) {
            return;
        }
        long cooldown = config.getNotifyCooldownSeconds() * 1000L;
        lastNotifyAt.entrySet().removeIf(e -> now - e.getValue() > cooldown);
    }
}
