package com.example.qqbot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 生成 docs/openapi.json。
 *
 * <p>OpenAPI 是接口契约的唯一来源：前端类型、SDK 与文档都从它生成。
 * 这个测试启动一次完整的 Web 上下文，拉取 springdoc 的 /v3/api-docs，
 * 把 servers 归一成 "/" 后写到仓库根目录的 docs/openapi.json。
 *
 * <p>默认 springdoc 是关闭的，生产不会暴露 /v3/api-docs；这里通过
 * {@code springdoc.api-docs.enabled=true} 只在测试里打开。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OpenApiSpecTest {

    private static final Path BASE = createBase();

    private static Path createBase() {
        try {
            return Files.createTempDirectory("qqbot-openapi-");
        } catch (IOException e) {
            throw new IllegalStateException("建不了临时目录", e);
        }
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("app.persistence.db", () -> BASE.resolve("openapi.sqlite").toString());
        r.add("app.kb.dir", () -> BASE.resolve("kb").toString());
        r.add("app.media.temp-images.dir", () -> BASE.resolve("tmp-images").toString());
        r.add("app.media.kb-images.dir", () -> BASE.resolve("kb-images").toString());
        r.add("app.site.carousel.dir", () -> BASE.resolve("site-images").toString());
        r.add("app.guard.file-access.allowed-roots[0]", () -> BASE.toString());
        r.add("springdoc.api-docs.enabled", () -> "true");
    }

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private ObjectMapper mapper;

    @Test
    @DisplayName("★ 生成 docs/openapi.json，并确认公开/管理接口都在里面")
    void generate() throws IOException {
        String json = rest.getForObject("/v3/api-docs", String.class);
        assertThat(json).isNotBlank();

        JsonNode root = mapper.readTree(json);
        assertThat(root.path("openapi").asText()).startsWith("3.");
        assertThat(root.path("paths").size()).isGreaterThan(50);
        assertThat(root.path("paths").has("/api/public/kb/search")).isTrue();
        assertThat(root.path("paths").has("/admin/api/kb/blocks")).isTrue();

        ObjectNode doc = (ObjectNode) root;
        doc.set("servers", mapper.createArrayNode().add(
                mapper.createObjectNode().put("url", "/")));
        ObjectNode info = (ObjectNode) doc.path("info");
        info.put("title", "Q-A-bot API");
        info.put("version", "1.0.0");
        doc.set("info", info);

        Path out = Path.of("..", "docs", "openapi.json").toAbsolutePath().normalize();
        Files.createDirectories(out.getParent());
        Files.writeString(out, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(root));
        assertThat(out).exists();
    }
}
