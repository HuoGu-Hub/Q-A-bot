package com.example.qqbot.publicapi;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 公开 API 的 HTTP 契约。
 *
 * <p>这些测试不验业务算法，只验外部能观察到的东西：路径、状态码、JSON 形状、
 * 缓存头与错误体。前端与第三方都按这份契约调用，改坏一个字段就应该在这里红。
 */
@SpringBootTest
@AutoConfigureMockMvc
class PublicApiContractTest {

    private static final Path BASE = createBase();

    private static Path createBase() {
        try {
            return Files.createTempDirectory("qqbot-public-api-");
        } catch (IOException e) {
            throw new IllegalStateException("建不了临时目录", e);
        }
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("app.persistence.db", () -> BASE.resolve("public.sqlite").toString());
        r.add("app.kb.dir", () -> BASE.resolve("kb").toString());
        r.add("app.media.temp-images.dir", () -> BASE.resolve("tmp-images").toString());
        r.add("app.media.kb-images.dir", () -> BASE.resolve("kb-images").toString());
        r.add("app.site.carousel.dir", () -> BASE.resolve("site-images").toString());
        r.add("app.guard.file-access.allowed-roots[0]", () -> BASE.toString());
    }

    @Autowired
    private MockMvc mvc;

    @Test
    @DisplayName("GET /api/public/v1/site：站点信息 + 60 秒缓存 + ETag")
    void site() throws Exception {
        mvc.perform(get("/api/public/v1/site"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(header().string("Cache-Control", containsString("max-age=60")))
                .andExpect(header().exists("ETag"))
                .andExpect(jsonPath("$.botName").exists())
                .andExpect(jsonPath("$.texts").isMap());
    }

    @Test
    @DisplayName("GET /api/public/v1/stats：公开统计字段固定")
    void stats() throws Exception {
        mvc.perform(get("/api/public/v1/stats"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", containsString("max-age=60")))
                .andExpect(jsonPath("$.questions").isNumber())
                .andExpect(jsonPath("$.hitRate").isNumber())
                .andExpect(jsonPath("$.kbEntries").isNumber())
                .andExpect(jsonPath("$.glossaryTerms").isNumber())
                .andExpect(jsonPath("$.since").isString());
    }

    @Test
    @DisplayName("GET /api/public/v1/kb/search：空查询返回空结果，搜索只缓存 30 秒")
    void search() throws Exception {
        mvc.perform(get("/api/public/v1/kb/search").param("q", ""))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", containsString("max-age=30")))
                .andExpect(jsonPath("$.query").value(""))
                .andExpect(jsonPath("$.count").value(0))
                .andExpect(jsonPath("$.mode").value("keyword"))
                .andExpect(jsonPath("$.entries", hasSize(0)));
    }

    @Test
    @DisplayName("GET /api/public/v1/kb/groups：分类是数组，不是被包一层对象")
    void groups() throws Exception {
        mvc.perform(get("/api/public/v1/kb/groups"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", containsString("max-age=300")))
                .andExpect(jsonPath("$").isArray());
    }

    @Test
    @DisplayName("GET /api/public/v1/kb/browse：分页字段与空页形状")
    void browse() throws Exception {
        mvc.perform(get("/api/public/v1/kb/browse").param("group", "不存在"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries").isArray())
                .andExpect(jsonPath("$.total").isNumber())
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.pageSize").value(40));
    }

    @Test
    @DisplayName("GET /api/public/v1/kb/page：缺标题 400，查不到 404，错误体是 problem+json")
    void pageErrors() throws Exception {
        mvc.perform(get("/api/public/v1/kb/page"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.code").exists());

        mvc.perform(get("/api/public/v1/kb/page").param("title", "绝对不存在的词条"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(404));
    }

    @Test
    @DisplayName("GET /api/public/v1/carousel：清单形状固定")
    void carousel() throws Exception {
        mvc.perform(get("/api/public/v1/carousel"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", containsString("max-age=60")))
                .andExpect(jsonPath("$.items").isArray())
                .andExpect(jsonPath("$.intervalMs").isNumber());
    }

    @Test
    @DisplayName("GET /api/public/v1/carousel/image/{id}：不存在回 404")
    void carouselImageMissing() throws Exception {
        mvc.perform(get("/api/public/v1/carousel/image/999999"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
    }

    @Test
    @DisplayName("GET /api/public/v1/map/catalog：地图目录字段固定")
    void mapCatalog() throws Exception {
        mvc.perform(get("/api/public/v1/map/catalog"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", containsString("max-age=300")))
                .andExpect(jsonPath("$.enabled").isBoolean())
                .andExpect(jsonPath("$.unitsPerMeter").isNumber())
                .andExpect(jsonPath("$.maxZoom").isNumber())
                .andExpect(jsonPath("$.categories").isArray())
                .andExpect(jsonPath("$.maps").isArray());
    }

    @Test
    @DisplayName("GET /api/public/v1/map/markers：缺 map 回 400")
    void mapMarkersMissingMap() throws Exception {
        mvc.perform(get("/api/public/v1/map/markers"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(400));
    }

    @Test
    @DisplayName("GET /api/public/v1/map/markers：有 map 时返回 map 与 markers")
    void mapMarkers() throws Exception {
        mvc.perform(get("/api/public/v1/map/markers").param("map", "Embervale"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.map").value("Embervale"))
                .andExpect(jsonPath("$.markers").isArray());
    }

    @Test
    @DisplayName("GET /api/public/v1/map/resource-groups：缺 map 回 400")
    void resourceGroupsMissingMap() throws Exception {
        mvc.perform(get("/api/public/v1/map/resource-groups"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
    }

    @Test
    @DisplayName("GET /api/public/v1/map/resources：缺 layer 回 400")
    void resourcesMissingLayer() throws Exception {
        mvc.perform(get("/api/public/v1/map/resources").param("map", "Embervale"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
    }

    @Test
    @DisplayName("GET /api/public/v1/map/resource-areas：缺 layer 回 400")
    void resourceAreasMissingLayer() throws Exception {
        mvc.perform(get("/api/public/v1/map/resource-areas").param("map", "Embervale"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
    }


    @Test
    @DisplayName("GET /api/public/v1/map/resource-groups：资源目录形状固定")
    void resourceGroups() throws Exception {
        mvc.perform(get("/api/public/v1/map/resource-groups").param("map", "Embervale"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", containsString("max-age=300")))
                .andExpect(jsonPath("$.map").value("Embervale"))
                .andExpect(jsonPath("$.imported").isBoolean())
                .andExpect(jsonPath("$.version").isString())
                .andExpect(jsonPath("$.categories").isArray());
    }

    @Test
    @DisplayName("GET /api/public/v1/map/resources：按层取点，未知层返回空数组")
    void resources() throws Exception {
        mvc.perform(get("/api/public/v1/map/resources")
                        .param("map", "Embervale")
                        .param("layer", "不存在的层"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", containsString("max-age=300")))
                .andExpect(jsonPath("$.map").value("Embervale"))
                .andExpect(jsonPath("$.layer").value("不存在的层"))
                .andExpect(jsonPath("$.points", hasSize(0)));
    }

    @Test
    @DisplayName("GET /api/public/v1/map/resource-areas：按层取面，未知层返回空数组")
    void resourceAreas() throws Exception {
        mvc.perform(get("/api/public/v1/map/resource-areas")
                        .param("map", "Embervale")
                        .param("layer", "不存在的层"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", containsString("max-age=300")))
                .andExpect(jsonPath("$.areas", hasSize(0)));
    }

    @Test
    @DisplayName("GET /api/public/v1/map/resource-icon：合法图标直接返回 PNG 与长缓存")
    void resourceIcon() throws Exception {
        mvc.perform(get("/api/public/v1/map/resource-icon").param("name", "ore-iron.png"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.IMAGE_PNG))
                .andExpect(header().string("Cache-Control", containsString("max-age=2592000")));
    }

    @Test
    @DisplayName("GET /api/public/v1/map/resource-icon：非法文件名回 400")
    void resourceIconRejectsPath() throws Exception {
        mvc.perform(get("/api/public/v1/map/resource-icon").param("name", "../secret.png"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("GET /api/public/v1/map/search：空词返回空结果")
    void mapSearch() throws Exception {
        mvc.perform(get("/api/public/v1/map/search").param("q", ""))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", containsString("max-age=30")))
                .andExpect(jsonPath("$.q").value(""))
                .andExpect(jsonPath("$.results", hasSize(0)));
    }

    @Test
    @DisplayName("GET /api/public/v1/map/locate：缺 q 回 400")
    void locateMissingQuery() throws Exception {
        mvc.perform(get("/api/public/v1/map/locate"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(400));
    }

    @Test
    @DisplayName("GET /api/public/v1/map/tile：白名单外资源直接 404")
    void tileRejectsUnknownAsset() throws Exception {
        MvcResult pending = mvc.perform(get("/api/public/v1/map/tile")
                        .param("map", "Embervale")
                        .param("name", "not-allowed.png"))
                .andExpect(request().asyncStarted())
                .andReturn();
        mvc.perform(asyncDispatch(pending))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("旧路径 /api/public/site 继续可用，但带 Deprecation / Sunset / Link")
    void legacyPathStillWorks() throws Exception {
        mvc.perform(get("/api/public/site"))
                .andExpect(status().isOk())
                .andExpect(header().string("Deprecation", "true"))
                .andExpect(header().exists("Sunset"))
                .andExpect(header().string("Link", containsString("/api/public/v1/site")))
                .andExpect(forwardedUrl("/api/public/v1/site"));
    }
}
