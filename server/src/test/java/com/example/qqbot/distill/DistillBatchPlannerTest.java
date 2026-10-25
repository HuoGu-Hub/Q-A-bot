package com.example.qqbot.distill;

import com.example.qqbot.persistence.ChatRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DistillBatchPlannerTest {

    private static ChatRepository.MessageRef ref(long id, String ts, int chars) {
        return new ChatRepository.MessageRef(id, ts, chars, 0);
    }

    private static DistillBatchPlanner.Options opts(String mode, int messages, int chars, int gap, int overlap) {
        return new DistillBatchPlanner.Options(mode, messages, chars, gap, overlap, 100);
    }

    @Test
    @DisplayName("按会话间隙断开：中间隔了 35 分钟就分两批")
    void splitsOnGap() {
        var messages = List.of(
                ref(1, "2026-10-20T10:00:00Z", 10),
                ref(2, "2026-10-20T10:05:00Z", 10),
                ref(3, "2026-10-20T10:40:00Z", 10));

        var plan = DistillBatchPlanner.plan(messages, opts("count", 80, 10000, 30, 0));

        assertThat(plan.batches()).hasSize(2);
        assertThat(plan.batches().get(0).messageFrom()).isEqualTo(1);
        assertThat(plan.batches().get(0).messageTo()).isEqualTo(2);
        assertThat(plan.batches().get(1).messageFrom()).isEqualTo(3);
    }

    @Test
    @DisplayName("按条数切：5 条、每批 2 条，切成 3 批")
    void splitsOnCount() {
        var messages = List.of(
                ref(1, "2026-10-20T10:00:00Z", 10),
                ref(2, "2026-10-20T10:01:00Z", 10),
                ref(3, "2026-10-20T10:02:00Z", 10),
                ref(4, "2026-10-20T10:03:00Z", 10),
                ref(5, "2026-10-20T10:04:00Z", 10));

        var plan = DistillBatchPlanner.plan(messages, opts("count", 2, 10000, 30, 0));

        assertThat(plan.batches()).hasSize(3);
        assertThat(plan.batches()).extracting(DistillBatchPlanner.Batch::messageCount)
                .containsExactly(2, 2, 1);
    }

    @Test
    @DisplayName("按字数切：每条约 400 字，单批 1000 字只能放两条")
    void splitsOnChars() {
        var messages = List.of(
                ref(1, "2026-10-20T10:00:00Z", 400),
                ref(2, "2026-10-20T10:01:00Z", 400),
                ref(3, "2026-10-20T10:02:00Z", 400));

        var plan = DistillBatchPlanner.plan(messages, opts("chars", 80, 1000, 30, 0));

        assertThat(plan.batches()).hasSize(2);
        assertThat(plan.batches().get(0).messageCount()).isEqualTo(2);
        assertThat(plan.batches().get(1).messageCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("重叠：相邻批次从上一批最后一条开始，不能原地打转")
    void overlapKeepsProgress() {
        var messages = List.of(
                ref(1, "2026-10-20T10:00:00Z", 10),
                ref(2, "2026-10-20T10:01:00Z", 10),
                ref(3, "2026-10-20T10:02:00Z", 10),
                ref(4, "2026-10-20T10:03:00Z", 10));

        var plan = DistillBatchPlanner.plan(messages, opts("count", 2, 10000, 30, 1));

        assertThat(plan.batches()).hasSizeGreaterThanOrEqualTo(3);
        assertThat(plan.batches().get(1).messageFrom()).isEqualTo(plan.batches().get(0).messageTo());
    }

    @Test
    @DisplayName("按天切：跨本地日期的两条必须分开")
    void splitsOnLocalDay() {
        var messages = List.of(
                ref(1, "2026-10-20T23:50:00+08:00", 10),
                ref(2, "2026-10-21T00:10:00+08:00", 10));

        var plan = DistillBatchPlanner.plan(messages, opts("day", 80, 10000, 30, 0));

        assertThat(plan.batches()).hasSize(2);
    }

    @Test
    @DisplayName("单条超长消息也要成一批，不能卡死")
    void oversizedSingleMessage() {
        var plan = DistillBatchPlanner.plan(List.of(ref(1, "2026-10-20T10:00:00Z", 50000)),
                opts("chars", 80, 1000, 30, 0));

        assertThat(plan.batches()).hasSize(1);
        assertThat(plan.batches().get(0).charCount()).isEqualTo(50000);
    }

    @Test
    @DisplayName("批次超过上限时明确报错，而不是悄悄截断")
    void rejectsTooManyBatches() {
        var messages = List.of(
                ref(1, "2026-10-20T10:00:00Z", 10),
                ref(2, "2026-10-20T11:00:00Z", 10),
                ref(3, "2026-10-20T12:00:00Z", 10));

        assertThatThrownBy(() -> DistillBatchPlanner.plan(messages,
                new DistillBatchPlanner.Options("count", 1, 10000, 30, 0, 1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("分批太多");
    }

    @Test
    @DisplayName("空消息返回空计划")
    void emptyPlan() {
        var plan = DistillBatchPlanner.plan(List.of(), opts("count", 80, 10000, 30, 0));
        assertThat(plan.batches()).isEmpty();
        assertThat(plan.messageCount()).isZero();
    }
}
