package com.example.qqbot.kb.map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 解析器验收 —— 用**真实抓下来的 wiki 页面**做夹具
 * （{@code server/src/test/resources/kb-map/}，四个文件对应四种页面形态）。
 *
 * <p>刻意不联网：解析是有明确输入输出的纯函数，联调留给同步服务的集成测试。
 */
class KbMapParserTest {

    private static String fixture(String name) throws IOException {
        try (InputStream in = KbMapParserTest.class.getResourceAsStream("/kb-map/" + name)) {
            assertThat(in).as("找不到夹具 " + name).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static KbMapParser.Parsed parse(String file, String page) throws IOException {
        return KbMapParser.parse(page, fixture(file));
    }

    @Test
    @DisplayName("形态一：有 markers 的页（Quests）—— 取出 marker 与 include")
    void questsPage() throws IOException {
        KbMapParser.Parsed p = parse("Map_Embervale_Quests.json", "Map:Embervale/Quests");

        assertThat(p.meta().map()).isEqualTo("Embervale");
        assertThat(p.markers()).hasSize(1);
        KbMapParser.Marker m = p.markers().get(0);
        assertThat(m.group()).isEqualTo("QuestLocation");
        assertThat(m.markerId()).isEqualTo("ClaimASpotForYourBase");
        assertThat(m.name()).isEqualTo("Claim A Spot For Your Base");
        // ★ article 是通往知识库的外键，必须解析出来
        assertThat(m.article()).isEqualTo("Quests/Claim A Spot For Your Base");
        assertThat(m.x()).isEqualTo(3724);
        assertThat(m.y()).isEqualTo(1415);

        assertThat(p.includes()).contains("Map:Embervale/Background", "Map:Embervale/Groups");
        assertThat(p.meta().topLeft()).containsExactly(0, 10240);
        assertThat(p.meta().bottomRight()).containsExactly(10240, 0);
    }

    @Test
    @DisplayName("形态二：background + groups + markers（Blackmire/Main）")
    void blackmireMain() throws IOException {
        KbMapParser.Parsed p = parse("Map_Blackmire_Main.json", "Map:Blackmire/Main");

        assertThat(p.meta().map()).isEqualTo("Blackmire");
        assertThat(p.markers()).hasSize(11);
        assertThat(p.markers()).extracting(KbMapParser.Marker::group).contains("Shroud_Root", "Sun_Temple");
        assertThat(p.groups()).isNotEmpty();
        assertThat(p.groups()).extracting(KbMapParser.Group::key).contains("Shroud_Root");
        // ★ 这张页是**旧的底图 schema**：background 是一整张图的文件名字符串，
        //   所以没有 tileSize。坐标范围就是图片像素。
        assertThat(p.meta().backgroundImage()).isEqualTo("Map-Blackmire.jpg");
        assertThat(p.meta().tileSize()).isNull();
        assertThat(p.meta().tileCount()).isZero();
        assertThat(p.meta().bottomRight()).containsExactly(1485, 1267);
        // 实测 marker 的 article 覆盖率 100%
        assertThat(p.markers()).allSatisfy(m -> assertThat(m.article()).isNotBlank());
    }

    @Test
    @DisplayName("形态三：只有 $fragment + groups 的页 —— 没有 markers 不算错")
    void groupsFragmentPage() throws IOException {
        KbMapParser.Parsed p = parse("Map_Embervale_Groups.json", "Map:Embervale/Groups");

        assertThat(p.markers()).isEmpty();
        assertThat(p.groups()).isNotEmpty();
        assertThat(p.meta().fragment()).isNotNull();
        // 这页没有 crs —— 必须容忍，而不是抛异常
        assertThat(p.meta().topLeft()).isNull();
        assertThat(p.groups()).allSatisfy(g -> assertThat(g.name()).isNotBlank());
    }

    @Test
    @DisplayName("形态四：只有 include 的组合根（Map:Embervale）—— 一条 marker 都没有")
    void compositionRoot() throws IOException {
        KbMapParser.Parsed p = parse("Map_Embervale.json", "Map:Embervale");

        assertThat(p.markers()).isEmpty();
        assertThat(p.includes()).hasSizeGreaterThan(3);
        assertThat(p.includes()).contains("Map:Embervale/Background", "Map:Embervale/Quests");
        assertThat(p.meta().map()).isEqualTo("Embervale");
    }

    @Test
    @DisplayName("★ 底图两套 schema 并存：新的瓦片集（Embervale/Background）")
    void tiledBackground() throws IOException {
        KbMapParser.Parsed p = parse("Map_Embervale_Background.json", "Map:Embervale/Background");

        // 新形态：8×8 张 1280px 瓦片 = 10240×10240
        assertThat(p.meta().tileSize()).containsExactly(1280, 1280);
        assertThat(p.meta().tileCount()).isEqualTo(64);
        assertThat(p.meta().backgroundImage()).isNull();
        assertThat(p.meta().topLeft()).containsExactly(0, 10240);
        assertThat(p.markers()).isEmpty();
    }

    @Test
    @DisplayName("★ 画布元信息要能落库：瓦片清单原样保留，四至与缩放范围都取出来")
    void canvasMetadataIsCaptured() throws IOException {
        KbMapParser.Parsed p = parse("Map_Embervale_Background.json", "Map:Embervale/Background");

        assertThat(p.meta().hasCrs()).isTrue();
        // 瓦片的排版原点是 at，不是 crs.topLeft —— 实测 Collectibles 那页两者不同
        assertThat(p.meta().tileAt()).containsExactly(0, 0);
        // 清单**原样**存 JSON：本地不重排版，对方加字段也不会被我们丢掉
        assertThat(p.meta().tilesJson()).contains("\"image\":\"0_0.jpg\"").contains("\"position\"");
        // 缩放范围来自 settings.zoom，前端算初始视图要用
        assertThat(p.meta().zoomMin()).isEqualTo(5);
        assertThat(p.meta().zoomMax()).isEqualTo(8);
        assertThat(p.meta().renderable()).isTrue();
        assertThat(p.meta().extent()).containsExactly(10240, 10240);
    }

    @Test
    @DisplayName("没有 crs 的分组页不能被当成画布 —— 存了也没法把坐标换算成像素")
    void groupPageIsNotRenderable() throws IOException {
        KbMapParser.Parsed p = parse("Map_Embervale_Groups.json", "Map:Embervale/Groups");

        assertThat(p.meta().hasCrs()).isFalse();
        assertThat(p.meta().renderable()).isFalse();
    }

    @Test
    @DisplayName("旧 schema 的单张整图同样是可渲染画布，只是瓦片清单为空")
    void legacySingleImageCanvas() throws IOException {
        KbMapParser.Parsed p = parse("Map_Blackmire_Main.json", "Map:Blackmire/Main");

        assertThat(p.meta().hasCrs()).isTrue();
        assertThat(p.meta().renderable()).isTrue();
        assertThat(p.meta().tilesJson()).isEmpty();
        assertThat(p.meta().extent()).containsExactly(1485, 1267);
    }

    @Test
    @DisplayName("mapOf：从页面名取地图名")
    void mapName() {
        assertThat(KbMapParser.mapOf("Map:Embervale/Lore")).isEqualTo("Embervale");
        assertThat(KbMapParser.mapOf("Map:Embervale")).isEqualTo("Embervale");
        assertThat(KbMapParser.mapOf("Map:EarlyAccess/Tabs/All")).isEqualTo("EarlyAccess");
        assertThat(KbMapParser.mapOf("Map:Update4/Blackmire")).isEqualTo("Update4");
    }

    @Test
    @DisplayName("坏输入抛 MapParseException（同步时按页捕获，不让一页坏掉整次同步）")
    void badInput() {
        assertThatThrownBy(() -> KbMapParser.parse("Map:X", "{ not json"))
                .isInstanceOf(KbMapParser.MapParseException.class);
        assertThatThrownBy(() -> KbMapParser.parse("Map:X", "[1,2,3]"))
                .isInstanceOf(KbMapParser.MapParseException.class);
    }

    @Test
    @DisplayName("缺 id 的 marker 被跳过（不编造身份）")
    void markersWithoutIdAreSkipped() {
        String json = "{\"markers\":{\"G\":[{\"name\":\"无 id\",\"x\":1,\"y\":2},"
                + "{\"id\":\"ok\",\"name\":\"有 id\",\"x\":3,\"y\":4}]}}";
        KbMapParser.Parsed p = KbMapParser.parse("Map:T/P", json);
        assertThat(p.markers()).hasSize(1);
        assertThat(p.markers().get(0).markerId()).isEqualTo("ok");
    }
}
