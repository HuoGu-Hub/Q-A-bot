package com.example.qqbot.eval;

import com.example.qqbot.agent.ChatService;
import com.example.qqbot.llm.JudgeModel;
import com.example.qqbot.persistence.EvalRepository;
import com.example.qqbot.trace.KbTrace;
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
 * LLM 判定接入后：质量这一维改由模型给分，规则不再重复出一条结论。
 * 模型用桩替换，验证的是"接进来了、落库类型对、失败标签对"，不是判定质量本身。
 */
@SpringBootTest
class EvalLlmJudgeTest {

    private static final Path BASE = tempBase();

    private static Path tempBase() {
        try {
            return Files.createTempDirectory("qqbot-eval-llmjudge-");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("app.persistence.db", () -> BASE.resolve("judge.sqlite").toString());
        r.add("app.kb.dir", () -> BASE.resolve("kb").toString());
        r.add("app.media.temp-images.dir", () -> BASE.resolve("tmp-images").toString());
        r.add("app.media.kb-images.dir", () -> BASE.resolve("kb-images").toString());
        r.add("app.site.carousel.dir", () -> BASE.resolve("site-images").toString());
        r.add("app.guard.file-access.allowed-roots[0]", () -> BASE.toString());
        r.add("app.eval.judge.enabled", () -> "true");
        r.add("app.eval.judge.sample-rate", () -> "1.0");
        r.add("app.eval.judge.samples", () -> "2");
    }

    @MockitoBean
    private ChatService chatService;

    @MockitoBean
    private JudgeModel judgeModel;

    @Autowired
    private EvalService service;

    @Autowired
    private EvalDatasetService datasets;

    @Autowired
    private EvalRepository repo;

    @Test
    @DisplayName("模型判定替代规则结论，落库类型为 llm")
    void llmJudgementReplacesRule() throws Exception {
        when(chatService.reply(any(), anyString(), any(), anyList(), any(), anyBoolean(), any()))
                .thenReturn(new ChatService.ReplyResult("废料杯在废料堆旁边。", 300,
                        new KbTrace(true,
                                List.of(new KbTrace.Hit("block-1", "废料杯", "doc-1", 0.9, "vector")),
                                0.9, 0.9, 1, 1, List.of(), 20), List.of()));
        when(judgeModel.ask(anyString(), anyString(), anyString()))
                .thenReturn("{\"score\": 2, \"reason\": \"没有正面回答问题\"}");

        EvalViews.Dataset dataset = datasets.create("llm-" + Instant.now().toEpochMilli(),
                "LLM 判定用例集", "", "test");
        datasets.addCase(dataset.id(), "entity-l1", "entity", "废料杯在哪", "block-1", "");
        EvalViews.Version version = datasets.freeze(dataset.id(), "test");
        long batchId = service.createBatch("LLM 判定批次", dataset.id(), version.id(), 1, 1,
                "offline", 1.0, "test");
        waitFor(batchId);

        Long runId = service.batchDetail(batchId, 10).items().get(0).runId();
        EvalViews.RunDetail run = service.runDetail(runId);
        assertThat(run.judges()).filteredOn(j -> j.dimension().equals("quality"))
                .hasSize(1)
                .allMatch(j -> "llm".equals(j.judgeType()))
                .anyMatch(j -> "FAIL".equals(j.verdict()) && "ANS_LOW_SCORE".equals(j.tag())
                        && j.score() == 2.0);
    }

    private EvalRepository.BatchRow waitFor(long batchId) throws InterruptedException {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(20));
        while (Instant.now().isBefore(deadline)) {
            EvalRepository.BatchRow b = repo.findBatch(batchId);
            if (b != null && ("completed".equals(b.status()) || "failed".equals(b.status()))) {
                return b;
            }
            Thread.sleep(150);
        }
        throw new AssertionError("批次没有在 20 秒内结束");
    }
}
