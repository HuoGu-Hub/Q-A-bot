package com.example.qqbot.command;

import com.example.qqbot.onebot.outbound.OutboundSender;
import com.example.qqbot.time.AppTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;

/**
 * 定时输出的投递器 —— 每 10 秒看一次有没有到点的。
 *
 * <h2>为什么是轮询而不是给每个任务排一个定时器</h2>
 * 任务存在库里，进程随时可能重启。内存里的定时器一重启就没了，
 * 而"库里每行还在不在"天然就是唯一事实。轮询的代价是每次一次索引查询，
 * 量级是每人每天几条，可以忽略。
 *
 * <h2>四件事按顺序做，顺序不能换</h2>
 * <ol>
 *   <li><b>先抢行再发</b>：{@link ScheduleStore#claimDue} 用条件更新把状态推到 sending，
 *       抢不到就不发 —— 重复发是用户最容易发现的故障；</li>
 *   <li><b>先判宽限期</b>：停机两小时后的补发只会让人困惑，标记过期比发出去好；</li>
 *   <li><b>渲染用发送时刻</b>：{@code {time}} 取的是现在，不是设置它的那一刻；</li>
 *   <li><b>失败要能重试，也能停下</b>：重试会换一个更晚的 fire_at，超过次数进终态。</li>
 * </ol>
 */
@Component
public class ScheduleRunner {

    private static final Logger log = LoggerFactory.getLogger(ScheduleRunner.class);

    /** 一个 tick 最多处理几条，防止积压时长时间占着调度线程 */
    private static final int BATCH = 20;

    /**
     * 一行停在「发送中」超过多少分钟就认定是卡住了。
     *
     * <p>2 分钟远大于一次发送的最坏耗时（等发车位加一次 HTTP），
     * 所以正常情况绝不会误判；而进程崩在这个窗口里时，最多 2 分钟它就会自己回来。
     */
    private static final int STALE_SENDING_MINUTES = 2;

    private final ScheduleStore store;
    private final SchedulePolicy policy;
    private final VariableRenderer renderer;
    private final OutboundSender outboundSender;

    public ScheduleRunner(ScheduleStore store, SchedulePolicy policy,
                          VariableRenderer renderer, OutboundSender outboundSender) {
        this.store = store;
        this.policy = policy;
        this.renderer = renderer;
        this.outboundSender = outboundSender;
    }

    @Scheduled(fixedDelayString = "${app.commands.schedule.tick-ms:10000}",
            initialDelayString = "${app.commands.schedule.initial-delay-ms:15000}")
    public void tick() {
        try {
            runOnce(Instant.now());
        } catch (Exception e) {
            // 定时任务里抛出去只会被 Spring 记一行；自己记清楚是哪一批出了问题
            log.error("[SCHED] 扫描定时输出失败", e);
        }
    }

    /**
     * 扫一轮。
     *
     * <p>单独抽出来而不是直接写在 {@link #tick()} 里，是为了测试能喂一个确定的时刻，
     * 不必等到定时器真的响。
     *
     * @return 这一轮实际取到的条数
     */
    public int runOnce(Instant now) {
        if (!policy.isEnabled() || !store.isAvailable()) {
            return 0;
        }
        // 先把卡在"发送中"太久的放回待发：进程停在半路时，那一行会永远扫不到
        int reclaimed = store.reclaimStale(now, STALE_SENDING_MINUTES);
        if (reclaimed > 0) {
            log.warn("[SCHED] {} 条卡在发送中超过 {} 分钟，已放回待发",
                    reclaimed, STALE_SENDING_MINUTES);
        }

        List<ScheduleTask> due = store.claimDue(now, BATCH);
        for (ScheduleTask task : due) {
            deliver(task, now);
        }
        return due.size();
    }

    private void deliver(ScheduleTask task, Instant now) {
        long lateMinutes = Duration.between(task.fireAt(), now).toMinutes();
        if (lateMinutes > policy.getGraceMinutes()) {
            store.markExpired(task);
            log.warn("[SCHED] #{} 已错过 {} 分钟（宽限 {} 分钟），标记过期不补发",
                    task.id(), lateMinutes, policy.getGraceMinutes());
            return;
        }

        String text = render(task, now);
        if (!StringUtils.hasText(text)) {
            store.markSent(task, now);
            log.warn("[SCHED] #{} 渲染后为空，跳过发送", task.id());
            return;
        }

        try {
            if (task.isGroup()) {
                outboundSender.sendToGroup(task.groupId(), text);
            } else {
                outboundSender.sendToPrivate(task.userId(), text);
            }
        } catch (Exception e) {
            retryOrFail(task, now, e.getMessage());
            return;
        }

        // 下一次由"规则 + 时刻组"推导，而不是"上次 + 一个周期"：
        // 一天三个时刻的公告，7:00 发完就该轮到 14:00
        Instant next = task.schedule().nextAfter(now);
        if (next != null) {
            store.rearm(task, next, now);
            log.info("[SCHED] #{} 已发出（{}），下次 {}", task.id(), task.repeatLabel(), next);
        } else {
            store.markSent(task, now);
            log.info("[SCHED] #{} 已发出（最后一次）", task.id());
        }
    }

    /**
     * 用<b>发送时刻</b>渲染内容。
     *
     * <p>{@code {user}} 是创建者 —— 发送时没有入站事件，只有任务行里存着的那个昵称，
     * 所以它取的是"当初设置这条的人"，不是每个看到的人。
     */
    private String render(ScheduleTask task, Instant now) {
        Map<String, String> args = store.argsOf(task);
        RenderContext ctx = RenderContext.scheduled(task.userId(), task.groupId(), task.isGroup(),
                task.nickname(), "", args, "", now);
        return renderer.render(task.content(), ctx);
    }

    private void retryOrFail(ScheduleTask task, Instant now, String error) {
        int attempts = task.attempts() + 1;
        if (attempts >= policy.getMaxAttempts()) {
            store.markFailed(task, error);
            log.error("[SCHED] #{} 连续失败 {} 次，停止重试：{}", task.id(), attempts, error);
            return;
        }
        Instant next = now.plusSeconds(policy.getRetryDelaySeconds());
        store.retryLater(task, error, next);
        log.warn("[SCHED] #{} 第 {} 次发送失败，{} 秒后重试：{}",
                task.id(), attempts, policy.getRetryDelaySeconds(), error);
    }
}
