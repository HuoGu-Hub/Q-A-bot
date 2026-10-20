package com.example.qqbot.command;

import com.example.qqbot.onebot.model.OneBotEvent;
import com.example.qqbot.onebot.outbound.OutboundSender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 到点发的板块：扫描、渲染、投递、推下次。
 *
 * <p>与旧的定时任务扫描器是同一套节奏，只是队列换成了 {@code bot_record}：
 * 一张表里既有立即展示的记录，也有到点发的，靠 {@code delivery} 区分。
 *
 * <p>{@code tick()} 只是薄壳，真正干活的是 {@link #runOnce(Instant)} ——
 * 测试能喂一个确定时刻，不必等定时器。
 */
@Component
public class BoardDeliveryRunner {

    private static final Logger log = LoggerFactory.getLogger(BoardDeliveryRunner.class);

    /** 一个 tick 最多处理几条，防止积压时长时间占着调度线程 */
    private static final int BATCH = 20;

    /** 停在「发送中」超过多少分钟认定卡住 */
    private static final int STALE_SENDING_MINUTES = 2;

    private final BoardRecordStore store;
    private final SchedulePolicy policy;
    private final VariableRenderer renderer;
    private final OutboundSender outboundSender;

    public BoardDeliveryRunner(BoardRecordStore store, SchedulePolicy policy,
                               VariableRenderer renderer, OutboundSender outboundSender) {
        this.store = store;
        this.policy = policy;
        this.renderer = renderer;
        this.outboundSender = outboundSender;
    }

    @Scheduled(fixedDelayString = "${app.commands.schedule.tick-ms:15000}",
            initialDelayString = "${app.commands.schedule.initial-delay-ms:15000}")
    public void tick() {
        try {
            runOnce(Instant.now());
        } catch (Exception e) {
            log.error("[BOARD] 扫描失败", e);
        }
    }

    public int runOnce(Instant now) {
        if (!policy.isEnabled() || !store.isAvailable()) {
            return 0;
        }
        int reclaimed = store.reclaimStale(now, STALE_SENDING_MINUTES);
        if (reclaimed > 0) {
            log.warn("[BOARD] {} 条卡在发送中超过 {} 分钟，已放回待发", reclaimed, STALE_SENDING_MINUTES);
        }
        List<BoardRecord> due = store.due(now, BATCH);
        for (BoardRecord record : due) {
            deliver(record, now);
        }
        return due.size();
    }

    private void deliver(BoardRecord record, Instant now) {
        long lateMinutes = record.fireAt() == null ? 0 : Duration.between(record.fireAt(), now).toMinutes();
        if (lateMinutes > policy.getGraceMinutes()) {
            store.markExpired(record);
            log.warn("[BOARD] #{} 已错过 {} 分钟，标记过期不补发", record.id(), lateMinutes);
            return;
        }

        String text = render(record, now);
        if (!StringUtils.hasText(text)) {
            store.markSent(record, now);
            log.warn("[BOARD] #{} 渲染后为空，跳过发送", record.id());
            return;
        }
        try {
            if (record.isGroup()) {
                outboundSender.sendToGroup(record.groupId(), text);
            } else {
                outboundSender.sendToPrivate(record.userId(), text);
            }
        } catch (Exception e) {
            retryOrFail(record, now, e.getMessage());
            return;
        }

        Instant next = record.schedule().nextAfter(now);
        if (next != null) {
            store.rearm(record, next, now);
            log.info("[BOARD] #{} 已发出（{}），下次 {}", record.id(), record.whenText(), next);
        } else {
            store.markSent(record, now);
            log.info("[BOARD] #{} 已发出（最后一次）", record.id());
        }
    }

    /** {time} 取发送那一刻，{arg.x} 取当初收到的参数 */
    private String render(BoardRecord record, Instant now) {
        RenderContext ctx = RenderContext.scheduled(record.userId(), record.groupId(),
                record.isGroup(), record.nickname(), "", record.params(), record.text(), now);
        return renderer.render(record.text(), ctx);
    }

    private void retryOrFail(BoardRecord record, Instant now, String error) {
        int attempts = record.attempts() + 1;
        if (attempts >= policy.getMaxAttempts()) {
            store.markFailed(record, error);
            log.error("[BOARD] #{} 连续失败 {} 次，停止重试：{}", record.id(), attempts, error);
        } else {
            store.retryLater(record, error, now.plusSeconds(policy.getRetryDelaySeconds()));
            log.warn("[BOARD] #{} 发送失败（第 {} 次），{} 秒后重试：{}",
                    record.id(), attempts, policy.getRetryDelaySeconds(), error);
        }
    }
}
