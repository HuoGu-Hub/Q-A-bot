package com.example.qqbot.llm;

import io.swagger.v3.oas.annotations.security.SecurityRequirement;

import io.swagger.v3.oas.annotations.tags.Tag;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import com.example.qqbot.web.ApiException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 模型配置接口（管理后台专用）。
 *
 * <p><b>只开放一项可改：模型 ID（model-name）。</b>
 * 其余（base-url / api-key / 能力 / 温度）一律只读 ——
 * 那些改错的代价是"机器人直接哑掉"或"泄露密钥"。
 *
 * <p><b>API Key 绝不回显</b>：只返回前 6 后 4 的掩码。
 */
@Tag(name = "Admin")
@SecurityRequirement(name = "adminSession")
@RestController
@RequestMapping("/admin/api/models")
public class ModelController {

    private static final Logger log = LoggerFactory.getLogger(ModelController.class);

    private final LlmPolicy props;
    private final LlmRouter router;
    private final com.example.qqbot.settings.OverridesFile overrides;

    public ModelController(LlmPolicy props, LlmRouter router,
                           com.example.qqbot.settings.OverridesFile overrides) {
        this.props = props;
        this.router = router;
        this.overrides = overrides;
    }


    /** 全部厂商的当前配置（key 已脱敏） */
    @GetMapping("")
    public Map<String, Object> list() {
        List<Map<String, Object>> providers = new ArrayList<>();
        for (Map.Entry<String, Provider> e : props.getProviders().entrySet()) {
            String name = e.getKey();
            Provider cfg = e.getValue();
            boolean configured = cfg.getApiKey() != null && !cfg.getApiKey().isBlank();
            boolean ready = router.readyProviders().contains(name);

            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", name);
            m.put("configured", configured);
            m.put("ready", ready);
            m.put("modelName", router.currentModelName(name) != null
                    ? router.currentModelName(name) : cfg.getModelName());
            providers.add(m);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("providers", providers);
        out.put("defaultProvider", props.getDefaultProvider());
        out.put("fallbackChain", router.fallbackChain());
        out.put("note", "只有模型 ID 可以修改，改完立即生效（不用重启）");
        return out;
    }

    /**
     * ★ 测试某个模型能不能用。
     *
     * <p><b>不改动任何现有状态</b> —— 建个临时客户端发一个最小请求。
     * 这是"改错就哑"的第一道防线：先测通了再替换。
     */
    @PostMapping("/{provider}/actions/test")
    public ResponseEntity<Map<String, Object>> test(@PathVariable String provider,
                                                    @RequestBody TestModelRequest body) {
        String modelName = body.modelName();
        String prompt = body.prompt();

        if (modelName == null) {
            // 没传就用当前的
            modelName = router.currentModelName(provider);
        }
        if (modelName == null) {
            throw ApiException.badRequest("需要 modelName");
        }

        log.info("[LLM] 连通性测试：{} / {}", provider, modelName);
        Map<String, Object> result = router.probe(provider, modelName, prompt);
        return ResponseEntity.ok(result);
    }

    /**
     * 保存模型 ID（**热生效**）。
     *
     * <p>建议先调 §/test§ 测通再保存。若直接保存，也建议传 §verify=true§
     * —— 那样会先测再换，测不通就拒绝，避免"改错就哑"。
     */
    @PutMapping("/{provider}")
    public ResponseEntity<Map<String, Object>> save(@PathVariable String provider,
                                                    @RequestBody SaveModelRequest body) {
        String modelName = body.modelName();
        boolean verify = Boolean.TRUE.equals(body.verify());

        if (modelName == null) {
            throw ApiException.badRequest("需要 modelName");
        }
        if (!props.getProviders().containsKey(provider)) {
            throw ApiException.notFound("没有这个厂商：" + provider);
        }

        String oldName = router.currentModelName(provider);

        // 先测再换 —— 避免改错就哑
        if (verify) {
            Map<String, Object> probeResult = router.probe(provider, modelName, null);
            if (!Boolean.TRUE.equals(probeResult.get("ok"))) {
                throw ApiException.badRequest("测试未通过，已拒绝保存：" + probeResult.get("error"));
            }
        }

        try {
            router.swapModelName(provider, modelName);
        } catch (Exception e) {
            throw ApiException.internal(e.getMessage());
        }

        // 持久化到覆盖层，重启后仍是新值
        persist(provider, modelName);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("provider", provider);
        out.put("oldModelName", oldName);
        out.put("newModelName", modelName);
        out.put("note", "已立即生效（无需重启）");
        return ResponseEntity.ok(out);
    }

    /**
     * 拉取该厂商可用的模型列表。
     *
     * <p>用于前端做"下拉 + 可手输" —— 避免手打模型 ID 出错
     * （这个坑踩过：模型名必须是真实 ID）。
     *
     * <p>不是所有厂商都提供这个接口，拉不到就返回空列表，前端自动退化成纯手输。
     */
    @GetMapping("/{provider}/available")
    public Map<String, Object> available(@PathVariable String provider) {
        Provider cfg = props.getProviders().get(provider);
        if (cfg == null) {
            throw ApiException.badRequest("没有这个厂商");
        }
        if (cfg.getApiKey() == null || cfg.getApiKey().isBlank()) {
            throw ApiException.badRequest("未配置 api-key");
        }

        String url = cfg.getBaseUrl().replaceAll("/+$", "") + "/models";
        try {
            HttpClient http = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(10)).build();
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(20))
                    .header("Authorization", "Bearer " + cfg.getApiKey())
                    .GET();
            if (cfg.getHeaders() != null) {
                cfg.getHeaders().forEach(b::header);
            }
            HttpResponse<String> resp = http.send(b.build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

            if (resp.statusCode() != 200) {
                // ★ 别把所有非 200 都说成"该厂商不提供列表接口"（2026-10-22 实测的误导：
                //   实际是 OpenCode Go 的 key 过期，401；人却以为要手打模型 ID）。
                String detail = ProviderError.readable(resp.body());
                String suffix = detail.isEmpty() ? "" : "：" + detail;
                if (ProviderError.isAuthError(resp.statusCode())) {
                    return Map.of("ok", false, "models", List.of(), "authError", true,
                            "error", "API Key 无效或已过期（HTTP " + resp.statusCode() + "）" + suffix);
                }
                // 404/405 才是"这家没有这个接口"的合理猜测
                String hint = resp.statusCode() == 404 || resp.statusCode() == 405
                        ? "（该厂商可能不提供模型列表接口，请手动输入）" : "";
                return Map.of("ok", false, "models", List.of(),
                        "error", "HTTP " + resp.statusCode() + suffix + hint);
            }

            List<String> ids = new ArrayList<>();
            var root = new com.fasterxml.jackson.databind.ObjectMapper().readTree(resp.body());
            for (var node : root.path("data")) {
                String id = node.path("id").asText("");
                if (!id.isBlank()) {
                    ids.add(id);
                }
            }
            ids.sort(String::compareTo);
            return Map.of("ok", true, "models", ids, "count", ids.size());
        } catch (Exception e) {
            return Map.of("ok", false, "models", List.of(),
                    "error", "拉取失败：" + ProviderError.readable(e.getMessage()));
        }
    }

    // ==================== 内部 ====================

    /**
     * 写入覆盖层 —— 重启后仍是新模型。
     *
     * <p>交给 OverridesFile 统一写。**不要在这里自己拼 YAML**：
     * 两边各写各的会产生重复顶层键，导致服务起不来（踩过这个坑）。
     */
    private void persist(String provider, String modelName) {
        overrides.put("app.llm.providers." + provider + ".model-name", modelName);
    }

    private static String str(Object o) {
        if (o == null) {
            return null;
        }
        String s = String.valueOf(o).trim();
        return s.isEmpty() ? null : s;
    }

    @JsonIgnoreProperties(ignoreUnknown = false)

    public record TestModelRequest(String modelName, String prompt) {
    }

    @JsonIgnoreProperties(ignoreUnknown = false)

    public record SaveModelRequest(String modelName, Boolean verify) {
    }
}
