package com.example.qqbot.eval;

import com.example.qqbot.agent.ChatService;
import com.example.qqbot.persistence.EvalRepository;
import com.example.qqbot.trace.KbTrace;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * 一条用例从建批到出结论的整条链路。
 *
 * <p>用桩替换模型调用，其余全部是真的：真检索器、真判定器、真库、真执行器线程。
 * 要验证的是"装配有没有接错"—— 这条路径断了，页面上所有数字都会是 0，
 * 而单测各自都是绿的。
 */
@SpringBootTest
class EvalFlowTest {

    private static final Path BASE = tempBase();

    private static Path tempBase() {
        try {
            return Files.createTempDirectory("qqbot-eval-");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("app.persistence.db", () -> BASE.resolve("eval-flow.sqlite").toString());
        r.add("app.kb.dir", () -> BASE.resolve("kb").toString());
        r.add("app.media.temp-images.dir", () -> BASE.resolve("tmp-images").toString());
        r.add("app.media.kb-images.dir", () -> BASE.resolve("kb-images").toString());
        r.add("app.site.carousel.dir", () -> BASE.resolve("site-images").toString());
        r.add("app.guard.file-access.allowed-roots[0]", () -> BASE.toString());
    }

    @MockitoBean
    private ChatService chatService;

    @Autowired
    private EvalService service;

    @Autowired
    private EvalDatasetService datasets;

    @Autowired
    private EvalRepository repo;

    @BeforeEach
    void stubModel() {
        KbTrace trace = new KbTrace(true,
                List.of(new KbTrace.Hit("block-1", "废料杯", "doc-1", 0.82, "vector")),
                0.8, 0.81, 3, 1, List.of(), 120);
        when(chatService.reply(any(), anyString(), any(), anyList(), any(), anyBoolean(), any()))
                .thenReturn(new ChatService.ReplyResult("废料杯在废料堆旁边。", 800, trace, List.of()));
    }

    @Test
    @DisplayName("建批 → 执行 → 判定 → 聚合：一条用例跑完整条链路并给出通过结论")
    void batchRunsEndToEnd() throws Exception {
        EvalViews.Dataset dataset = datasets.create("flow-" + Instant.now().toEpochMilli(),
                "流程用例集", "", "test");
        datasets.addCase(dataset.id(), "entity-1", "entity", "废料杯在哪", "block-1", "");
        EvalViews.Version version = datasets.freeze(dataset.id(), "test");
        assertThat(version).isNotNull();
        assertThat(version.caseCount()).isEqualTo(1);

        long batchId = service.createBatch("流程批次", dataset.id(), version.id(), 2, 2,
                "offline", 1.0, "test");

        EvalRepository.BatchRow batch = waitForBatch(batchId);
        assertThat(batch.status()).isEqualTo("completed");
        assertThat(batch.itemTotal()).isEqualTo(1);

        EvalViews.Summary summary = service.summary(batchId);
        assertThat(summary.runs()).isEqualTo(2);
        assertThat(summary.pass()).isEqualTo(2);
        assertThat(summary.passRate()).isEqualTo(1.0);
        assertThat(batch.gateStatus()).isEqualTo("passed");

        EvalViews.BatchDetail detail = service.batchDetail(batchId, 20);
        assertThat(detail.items()).hasSize(1);
        assertThat(detail.items().get(0).attempts()).isEqualTo(2);
        Long runId = detail.items().get(0).runId();
        assertThat(runId).isNotNull();

        EvalViews.RunDetail run = service.runDetail(runId);
        assertThat(run.spans()).extracting(EvalViews.Span::kind)
                .contains("request", "retrieve", "llm", "judge", "outbound");
        assertThat(run.retrieval()).isNotNull();
        assertThat(run.retrieval().hits()).hasSize(1);
        assertThat(run.judges()).extracting(EvalViews.Judge::dimension)
                .contains("rag", "quality", "stability");
        assertThat(run.judges()).filteredOn(j -> j.dimension().equals("stability"))
                .allMatch(j -> j.verdict().equals("PASS"));
        assertThat(run.question()).isEqualTo("废料杯在哪");
    }

    @Test
    @DisplayName("质量失败自动进复核；提交人工结论后覆盖自动判定")
    void reviewFlow() throws Exception {
        KbTrace trace = new KbTrace(true,
                List.of(new KbTrace.Hit("block-1", "废料杯", "doc-1", 0.82, "vector")),
                0.8, 0.81, 3, 1, List.of(), 120);
        when(chatService.reply(any(), anyString(), any(), anyList(), any(), anyBoolean(), any()))
                .thenAnswer(invocation -> "空白".equals(invocation.getArgument(1))
                        ? new ChatService.ReplyResult("", 300, trace, List.of())
                        : new ChatService.ReplyResult("废料杯在废料堆旁边。", 800, trace, List.of()));

        EvalViews.Dataset dataset = datasets.create("review-" + Instant.now().toEpochMilli(),
                "复核用例集", "", "test");
        datasets.addCase(dataset.id(), "blank-1", "entity", "空白", "block-1", "");
        EvalViews.Version version = datasets.freeze(dataset.id(), "test");
        long batchId = service.createBatch("复核批次", dataset.id(), version.id(), 1, 1,
                "offline", 1.0, "test");
        waitForBatch(batchId);

        List<EvalViews.Review> pending = service.reviews("pending", 10);
        assertThat(pending).isNotEmpty();
        EvalViews.Review task = pending.get(0);
        assertThat(task.reason()).contains("空返回");

        assertThat(service.claimReview(task.id(), "tester")).isTrue();
        assertThat(service.submitReview(task.id(), "PASS", "人工确认是模型抖动", "tester")).isTrue();

        EvalViews.RunDetail run = service.runDetail(task.runId());
        assertThat(run.judges()).filteredOn(j -> j.dimension().equals("quality"))
                .anyMatch(j -> j.verdict().equals("PASS") && j.reason().contains("人工确认"));
        assertThat(service.reviews("pending", 10)).isEmpty();
    }

    @Test
    @DisplayName("基线晋升后，下一批回退超过 3 个百分点直接判阻断")
    void baselineBlocksRegression() throws Exception {
        KbTrace trace = new KbTrace(true,
                List.of(new KbTrace.Hit("block-1", "废料杯", "doc-1", 0.82, "vector")),
                0.8, 0.81, 3, 1, List.of(), 120);
        when(chatService.reply(any(), anyString(), any(), anyList(), any(), anyBoolean(), any()))
                .thenReturn(new ChatService.ReplyResult("废料杯在废料堆旁边。", 800, trace, List.of()));

        EvalViews.Dataset dataset = datasets.create("baseline-" + Instant.now().toEpochMilli(),
                "基线用例集", "", "test");
        datasets.addCase(dataset.id(), "entity-b", "entity", "废料杯在哪", "block-1", "");
        EvalViews.Version version = datasets.freeze(dataset.id(), "test");

        long goodBatch = service.createBatch("基线批次", dataset.id(), version.id(), 1, 1,
                "offline", 1.0, "test");
        waitForBatch(goodBatch);
        EvalViews.Baseline baseline = service.createBaseline(goodBatch, "v1 基线");
        assertThat(service.promoteBaseline(baseline.id(), "test")).isTrue();

        when(chatService.reply(any(), anyString(), any(), anyList(), any(), anyBoolean(), any()))
                .thenReturn(new ChatService.ReplyResult("", 300, trace, List.of()));
        long badBatch = service.createBatch("回退批次", dataset.id(), version.id(), 1, 1,
                "offline", 1.0, "test");
        EvalRepository.BatchRow row = waitForBatch(badBatch);
        assertThat(row.gateStatus()).isEqualTo("blocked");
        assertThat(row.gateReason()).contains("基线");
    }

    /** 批次在独立线程里跑，这里等它进终态；超时就把当时状态报出来 */
    private EvalRepository.BatchRow waitForBatch(long batchId) throws InterruptedException {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(20));
        while (Instant.now().isBefore(deadline)) {
            EvalRepository.BatchRow batch = repo.findBatch(batchId);
            if (batch != null && ("completed".equals(batch.status()) || "failed".equals(batch.status()))) {
                return batch;
            }
            Thread.sleep(150);
        }
        EvalRepository.BatchRow batch = repo.findBatch(batchId);
        throw new AssertionError("批次没有在 20 秒内结束，当前状态：" + (batch == null ? "无" : batch.status()));
    }
}
