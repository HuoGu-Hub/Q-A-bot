package com.example.qqbot.guard.stage;

import com.example.qqbot.config.GuardProperties;
import com.example.qqbot.guard.BlockNotifier;
import com.example.qqbot.guard.GuardContext;
import com.example.qqbot.guard.GuardResult;
import com.example.qqbot.guard.GuardStage;
import com.example.qqbot.guard.RateLimiter;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 频率限制：防止高频回复炸群。
 *
 * <p>两个维度同时生效，**任意一个超限就拦**：
 * <ul>
 *   <li><b>每群每分钟最多 N 条</b> —— 防止整个群被刷屏</li>
 *   <li><b>每人在【同一个群】每分钟最多 M 次</b> —— 防止单个人连续追问。
 *       注意是「群内」统计，同一个人在不同群里互不影响。</li>
 * </ul>
 *
 * <p>计数发生在**决定要不要回复的时刻**（也就是这一层），而不是消息发送之后。
 * 这样并发到达的消息不会在计数前「全部挤过闸门」。
 *
 * <p>被拦之后要不要回一句提示，交给 {@link BlockNotifier} 判断（它带两层限流）。
 */
@Component
public class RateLimitStage implements GuardStage {

    /** 群维度的窗口固定 60 秒（语义就是「每群每分钟 N 条」） */
    private static final long GROUP_WINDOW_MILLIS = 60_000L;

    private final GuardProperties.RateLimit config;
    private final RateLimiter limiter;
    private final BlockNotifier notifier;

    public RateLimitStage(GuardProperties properties, RateLimiter limiter, BlockNotifier notifier) {
        this.config = properties.getRateLimit();
        this.limiter = limiter;
        this.notifier = notifier;
    }

    @Override
    public String name() {
        return "rate-limit";
    }

    @Override
    public GuardResult check(GuardContext ctx) {
        if (!config.isEnabled()) {
            return GuardResult.pass();
        }

        // 注意 user 维度的 key 带上了 groupId —— 同一个人在别的群里不受影响
        long userWindowMillis = config.getPerUserWindowSeconds() * 1000L;
        RateLimiter.Bucket userBucket = null;
        if (ctx.userId() != null && config.getPerUserPerMinute() > 0 && userWindowMillis > 0) {
            String userKey = ctx.groupId() != null
                    ? "group:" + ctx.groupId() + ":user:" + ctx.userId()
                    : "user:" + ctx.userId();
            userBucket = new RateLimiter.Bucket(userKey, userWindowMillis, config.getPerUserPerMinute());
        }

        RateLimiter.Bucket groupBucket = null;
        if (ctx.groupId() != null && config.getPerGroupPerMinute() > 0) {
            groupBucket = new RateLimiter.Bucket("group:" + ctx.groupId(), GROUP_WINDOW_MILLIS,
                    config.getPerGroupPerMinute());
        }

        List<RateLimiter.Bucket> buckets = new ArrayList<>();
        if (userBucket != null) {
            buckets.add(userBucket);
        }
        if (groupBucket != null) {
            buckets.add(groupBucket);
        }
        if (buckets.isEmpty()) {
            return GuardResult.pass();
        }

        // 先判断是哪一维度超了（只为决定回哪句提示，不影响安全性）
        String which = null;
        if (userBucket != null && limiter.wouldExceed(userBucket)) {
            which = "user";
        } else if (groupBucket != null && limiter.wouldExceed(groupBucket)) {
            which = "group";
        }

        if (limiter.tryAcquireAll(buckets)) {
            return GuardResult.pass();
        }
        if (which == null) {
            which = "user";
        }

        if (!"notify-once".equalsIgnoreCase(config.getOnLimit())) {
            return GuardResult.drop(name(), "超出频率限制（" + which + "）");
        }
        // {seconds} 用生效窗口替换 —— 窗口可配了，话术里就不能写死"一分钟"
        String text = "group".equals(which)
                ? config.getNotifyGroupText().replace("{seconds}", String.valueOf(GROUP_WINDOW_MILLIS / 1000))
                : config.getNotifyUserText().replace("{seconds}", String.valueOf(config.getPerUserWindowSeconds()));
        if (notifier.allow(ctx.userId(), ctx.groupId())) {
            return GuardResult.reply(name(), "超出频率限制（" + which + "）", text);
        }
        return GuardResult.drop(name(), "超出频率限制（" + which + "，提示已冷却）");
    }
}
