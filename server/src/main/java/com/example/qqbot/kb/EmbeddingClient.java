package com.example.qqbot.kb;

import com.example.qqbot.config.KbProperties;
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
import java.util.Comparator;
import java.util.List;

/**
 * 向量模型客户端（OpenAI 兼容的 {@code /v1/embeddings}）。
 *
 * <p>建索引和在线检索**共用同一个客户端**，这不是偷懒而是必须的：
 * 向量模型定义了坐标系，**建索引用哪个模型，查询就必须用哪个**，
 * 否则算出来的相似度毫无意义。
 *
 * <p>失败一律抛 {@link EmbeddingException}：建索引时应当直接失败（宁可不建，也别建半个），
 * 在线检索时由调用方捕获并降级到 B 路。
 */
@Component
public class EmbeddingClient {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingClient.class);

    private final KbProperties props;
    private final ObjectMapper mapper;
    private final HttpClient http;

    public EmbeddingClient(KbProperties props, ObjectMapper mapper) {
        this.props = props;
        this.mapper = mapper;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
    }

    /** 配置了 key 才能用 */
    public boolean isAvailable() {
        return props.isEnabled() && StringUtils.hasText(props.getEmbedding().getApiKey());
    }

    public int dimensions() {
        return props.getEmbedding().getDimensions();
    }

    /** 批量向量化，返回顺序与输入严格一致 */
    public List<float[]> embed(List<String> texts) {
        List<float[]> out = new ArrayList<>();
        if (texts == null || texts.isEmpty()) {
            return out;
        }
        KbProperties.Embedding cfg = props.getEmbedding();

        ObjectNode body = mapper.createObjectNode();
        body.put("model", cfg.getModel());
        ArrayNode input = body.putArray("input");
        texts.forEach(input::add);
        body.put("encoding_format", "float");

        String payload;
        try {
            payload = mapper.writeValueAsString(body);
        } catch (Exception e) {
            throw new EmbeddingException("构造向量化请求体失败：" + e.getMessage(), e);
        }

        HttpRequest request = HttpRequest.newBuilder(
                        URI.create(cfg.getBaseUrl().replaceAll("/+$", "") + "/embeddings"))
                .timeout(Duration.ofSeconds(cfg.getTimeoutSeconds()))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + cfg.getApiKey())
                .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new EmbeddingException("调用向量模型失败：" + e.getMessage(), e);
        }
        if (response.statusCode() != 200) {
            throw new EmbeddingException("向量模型 HTTP " + response.statusCode() + "："
                    + response.body().substring(0, Math.min(200, response.body().length())));
        }

        try {
            JsonNode root = mapper.readTree(response.body());
            JsonNode data = root.path("data");
            if (!data.isArray() || data.size() != texts.size()) {
                throw new EmbeddingException("向量模型返回条数不对：期望 " + texts.size()
                        + "，实际 " + (data.isArray() ? data.size() : "非数组"));
            }
            List<JsonNode> items = new ArrayList<>();
            data.forEach(items::add);
            items.sort(Comparator.comparingInt(n -> n.path("index").asInt()));
            for (JsonNode item : items) {
                JsonNode vec = item.path("embedding");
                float[] arr = new float[vec.size()];
                for (int i = 0; i < arr.length; i++) {
                    arr[i] = (float) vec.get(i).asDouble();
                }
                out.add(arr);
            }
        } catch (EmbeddingException e) {
            throw e;
        } catch (Exception e) {
            throw new EmbeddingException("解析向量模型响应失败：" + e.getMessage(), e);
        }

        int dim = out.isEmpty() ? 0 : out.get(0).length;
        if (cfg.getDimensions() > 0 && dim != cfg.getDimensions()) {
            log.warn("[KB] 向量维度与配置不符：实际 {}，配置 {}（app.kb.embedding.dimensions）", dim, cfg.getDimensions());
        }
        return out;
    }

    public float[] embedOne(String text) {
        List<float[]> r = embed(List.of(text));
        return r.isEmpty() ? null : r.get(0);
    }

    /**
     * 分批向量化：按 {@code app.kb.embedding.batch-size} 切成多次调用。
     *
     * <p>为什么要有它：导入一份两百块的文档时，"两百次 HTTP" 和 "二十次 HTTP"
     * 是几十秒和几秒的差别。标题向量（每个块一条短文本）尤其适合走这里。
     *
     * <p>返回顺序与输入严格一致；任何一批失败都照旧抛 {@link EmbeddingException}，
     * 由调用方决定是中止还是降级。
     */
    public List<float[]> embedChunked(List<String> texts) {
        List<float[]> out = new ArrayList<>();
        if (texts == null || texts.isEmpty()) {
            return out;
        }
        int size = Math.max(1, props.getEmbedding().getBatchSize());
        for (int i = 0; i < texts.size(); i += size) {
            out.addAll(embed(texts.subList(i, Math.min(i + size, texts.size()))));
        }
        return out;
    }

    /** 向量化失败 */
    public static class EmbeddingException extends RuntimeException {
        public EmbeddingException(String message) {
            super(message);
        }

        public EmbeddingException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
