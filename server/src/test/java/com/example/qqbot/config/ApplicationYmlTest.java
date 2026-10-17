package com.example.qqbot.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code application.yml} 的**体检** —— 让"配置写错"在构建期就暴露，而不是启动时。
 *
 * <h2>为什么需要它</h2>
 * 这份 yml 是**生产配置**，而它此前**没有任何测试覆盖**：拼错一个键、缩进错一格、
 * 把某个 {@code *-on-start} 从 {@code false} 改成 {@code true}，
 * 构建全绿、测试全过 —— 直到部署启动那一刻才发现。
 *
 * <p>而这个项目**正要反复改它**（扩充语料就是往 {@code wiki-import.categories} 里加分类），
 * 所以把体检做进构建是划算的。
 *
 * <h2>⚠️ 这里守的是"安全默认值"</h2>
 * 三个 {@code *-on-start} 必须是 {@code false}：它们是**运维动作**。
 * 一旦变成 {@code true}，每次重启都会去拉 wiki、调向量模型 —— 又慢又花钱，
 * 而且是"悄悄发生"的（没人会想到重启一次就触发一次全量导入）。
 */
class ApplicationYmlTest {

    private static Map<String, Object> load() throws Exception {
        try (InputStream in = java.nio.file.Files.newInputStream(
                java.nio.file.Path.of("src/main/resources/application.yml"))) {
            return new Yaml().load(in);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> at(Map<String, Object> root, String... path) {
        Map<String, Object> cur = root;
        for (String p : path) {
            Object next = cur.get(p);
            assertThat(next).as("配置缺少 %s（在 %s 下）", p, String.join(".", path)).isInstanceOf(Map.class);
            cur = (Map<String, Object>) next;
        }
        return cur;
    }

    @Test
    @DisplayName("★ application.yml 能解析（拼错/缩进错在这里就红，不用等启动）")
    void parses() throws Exception {
        assertThat(load()).isNotNull().containsKey("app");
    }

    @Test
    @DisplayName("★ 两个「启动时自动跑」的开关必须保持 false —— 它们是运维动作（wiki 导入已改为页面触发）")
    void autoRunSwitchesStayOff() throws Exception {
        Map<String, Object> root = load();
        assertThat(at(root, "app", "kb", "map-sync")).containsEntry("sync-on-start", false);
        assertThat(at(root, "app", "kb", "location-corpus")).containsEntry("build-on-start", false);
        // wiki 导入的 import-on-start 已删除：导入改由管理端「知识库 → 抓取」页触发，
        // 所以它不再是"启动开关"，而是页面上的按钮（范围存 kb_wiki_source 表）。
    }

    @Test
    @DisplayName("★ 语料入口的配置合法：来源非空、上限非负")
    void corpusEntryPointsAreSane() throws Exception {
        Map<String, Object> wiki = at(load(), "app", "kb", "wiki-import");
        assertThat((List<?>) wiki.get("prefixes")).as("prefixes 不能空").isNotEmpty();
        assertThat((List<?>) wiki.get("categories")).as("categories 不能空").isNotEmpty();
        assertThat(((List<?>) wiki.get("categories"))).allSatisfy(c ->
                assertThat(c).as("分类名不能有首尾空格（列页会匹配不到）")
                        .isInstanceOf(String.class).isEqualTo(((String) c).trim()));
        assertThat((Integer) wiki.get("max-body-chars")).as("单块上限必须为正").isPositive();
        // 0 = **不限**（默认）—— 页数会随游戏更新长大，固定上限必然被超过（见 WikiImport）
        assertThat((Integer) wiki.get("max-pages-per-source"))
                .as("每来源页数上限：0 = 不限，负数才是写错").isNotNegative();

        Map<String, Object> mapSync = at(load(), "app", "kb", "map-sync");
        assertThat((Integer) mapSync.get("max-pages")).isPositive();
        assertThat((Integer) mapSync.get("batch-size")).isPositive();
    }

    @Test
    @DisplayName("★ map-sync 不再有地图白名单 —— 判断「用哪张图」不该固化在配置里")
    void mapSyncHasNoMapAllowlist() throws Exception {
        Map<String, Object> mapSync = at(load(), "app", "kb", "map-sync");
        // 白名单是"哪张地图是当前世界"这个会变的判断 —— 写进配置就会在改名后静默失效。
        // 它现在由 kb_map_active 表 + 后台「地图」页决定（见 kb.map.MapScope）
        assertThat(mapSync).doesNotContainKey("maps");
    }

    @Test
    @DisplayName("★ 数据库键在 app.persistence 下，不在 app.qa 下（2026-10-02 改名）")
    void databaseKeysLiveUnderPersistence() throws Exception {
        Map<String, Object> root = load();
        Map<String, Object> persist = at(root, "app", "persistence");
        assertThat(persist).containsKeys("enabled", "db");
        assertThat((String) persist.get("db")).endsWith(".sqlite");
        // 旧键必须**不再存在** —— 留着会让人以为它还有效，而它已经被忽略了
        assertThat(at(root, "app", "qa")).as("app.qa.db 已改名为 app.persistence.db")
                .doesNotContainKey("db");
        assertThat(at(root, "app", "qa")).as("app.qa.enabled 只管记录，不再关库")
                .containsKey("enabled");
    }

    @Test
    @DisplayName("检索阈值在合理区间（改了它黄金集会说话，这里只挡明显的手滑）")
    void retrievalThresholdsAreSane() throws Exception {
        Map<String, Object> kb = at(load(), "app", "kb");
        assertThat((Double) kb.get("min-score")).isBetween(0.0, 1.0);
        assertThat((Double) kb.get("title-gate")).isBetween(0.0, 1.0);
        assertThat((Integer) kb.get("top-k")).isPositive();
    }
}
