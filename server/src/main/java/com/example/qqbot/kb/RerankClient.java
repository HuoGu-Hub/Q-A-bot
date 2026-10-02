package com.example.qqbot.kb;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * 重排模型客户端（cross-encoder，{@code /v1/rerank}）。
 *
 * <h2>它和 {@link EmbeddingClient} 的区别（决定了它为什么值得多一次调用）</h2>
 * 向量模型是**双塔**：问题和文档各自独立编码，只能比"大意像不像"，
 * 在中文短句上表现为**基线高、方差小** —— 实测"该答"的问题最低 0.548，
 * "库里根本没有"的问题最高 0.559，**分布完全重叠**，拿绝对值卡阈值是死路。
 *
 * <p>重排模型把问题和文档**拼在一起**看，因此能判断"这段文字到底回不回答这个问题"，
 * 输出的是**有区分度的相关度**：同一黄金集上，闲聊 top 分 ≤0.002，真实问题 ≥0.04。
 *
 * <h2>失败语义</h2>
 * 抛 {@link RerankException}。调用方 {@link KbRetriever} 会**保持原 RRF 顺序继续回答**
 * （fail-open）—— 重排是锦上添花，它挂了不能让机器人不回答。
 *
 * <p>与 {@link EmbeddingClient} 的另一个区别：**它不参与建索引**，
 * 所以换模型不需要重建向量库。
 */
@Component
public class RerankClient {

    private static final Logger log = LoggerFactory.getLogger(RerankClient.class);

    private final KbPolicy props;
    private final ObjectMapper mapper;
    private final HttpClient http;

    public RerankClient(KbPolicy props, ObjectMapper mapper) {
        this.props = props;
        this.mapper = mapper;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
    }

    /** 开了开关并且配了 key 才能用 */
    public boolean isAvailable() {
        Rerank cfg = props.getRerank();
        return props.isEnabled() && cfg.isEnabled() && StringUtils.hasText(cfg.getApiKey());
    }

    /**
     * 给一批文档打分。
     *
     * @return 与 {@code documents} **严格同序**的分数（0~1，越大越相关）。
     *         空输入返回空数组，不发起请求。
     * @throws RerankException 网络/协议/解析失败
     */
    public double[] score(String query, List<String> documents) {
        if (documents == null || documents.isEmpty()) {
            return new double[0];
        }
        Rerank cfg = props.getRerank();

        ObjectNode body = mapper.createObjectNode();
        body.put("model", cfg.getModel());
        body.put("query", query);
        ArrayNode docs = body.putArray("documents");
        documents.forEach(docs::add);
        body.put("top_n", documents.size());
        // 只要分数，不要服务端排序 —— 我们按 index 自己映射回原顺序
        body.put("return_documents", false);

        String payload;
        try {
            payload = mapper.writeValueAsString(body);
        } catch (Exception e) {
            throw new RerankException("构造重排请求体失败：" + e.getMessage(), e);
        }

        HttpRequest request = HttpRequest.newBuilder(
                        URI.create(cfg.getBaseUrl().replaceAll("/+$", "") + "/rerank"))
                .timeout(Duration.ofSeconds(cfg.getTimeoutSeconds()))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + cfg.getApiKey())
                .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new RerankException("调用重排模型失败：" + e.getMessage(), e);
        }
        if (response.statusCode() != 200) {
            throw new RerankException("重排模型 HTTP " + response.statusCode() + "："
                    + response.body().substring(0, Math.min(200, response.body().length())));
        }

        double[] out = new double[documents.size()];
        boolean[] filled = new boolean[documents.size()];
        try {
            JsonNode results = mapper.readTree(response.body()).path("results");
            if (!results.isArray()) {
                throw new RerankException("重排响应里没有 results 数组");
            }
            for (JsonNode r : results) {
                int idx = r.path("index").asInt(-1);
                if (idx < 0 || idx >= out.length) {
                    continue;
                }
                out[idx] = r.path("relevance_score").asDouble(0);
                filled[idx] = true;
            }
        } catch (RerankException e) {
            throw e;
        } catch (Exception e) {
            throw new RerankException("解析重排响应失败：" + e.getMessage(), e);
        }

        // 服务端没给分数的位置补 0（宁可当"不相关"，也不要抛异常让整次检索降级）
        int missing = 0;
        for (boolean b : filled) {
            if (!b) {
                missing++;
            }
        }
        if (missing > 0) {
            log.warn("[KB-RERANK] 有 {} 条没拿到分数（按 0 处理）", missing);
        }
        return out;
    }

    /** 重排失败。调用方应捕获并退回原顺序 */
    public static class RerankException extends RuntimeException {
        public RerankException(String message) {
            super(message);
        }

        public RerankException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** 供启动日志/排查 */
    public String describe() {
        Rerank cfg = props.getRerank();
        List<String> parts = new ArrayList<>();
        parts.add("enabled=" + cfg.isEnabled());
        parts.add("model=" + cfg.getModel());
        parts.add("候选=" + cfg.getCandidateLimit());
        parts.add("min=" + cfg.getMinScore());
        return String.join(" ", parts);
    }
}
