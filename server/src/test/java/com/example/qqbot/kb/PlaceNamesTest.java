package com.example.qqbot.kb;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 地名翻译的验收：常见参照点要中文，专有名词保英文，纯中文不二次翻译。 */
class PlaceNamesTest {

    @Test
    @DisplayName("资源截图里常见的参照点走完整译名")
    void translatesKnownPlaces() {
        assertThat(PlaceNames.zh("Research Camp")).isEqualTo("研究营地");
        assertThat(PlaceNames.zh("Vukah Arena")).isEqualTo("武卡竞技场");
        assertThat(PlaceNames.zh("Wistful Fields")).isEqualTo("怅惘田野");
        assertThat(PlaceNames.zh("Southern Caravan Camp")).isEqualTo("南部商队营地");
        assertThat(PlaceNames.zh("Night Sanctum")).isEqualTo("夜之圣所");
    }

    @Test
    @DisplayName("中英混合名只翻英文部分，纯中文原样返回")
    void keepsChineseAndTranslatesEnglishPart() {
        assertThat(PlaceNames.zh("火焰神龛·游牧高地")).isEqualTo("火焰神龛·游牧高地");
        assertThat(PlaceNames.zh("Night Sanctum·游牧高地")).isEqualTo("夜圣所·游牧高地");
        assertThat(PlaceNames.zh("Revelwood Hollow Halls")).isEqualTo("启示林空洞大厅");
    }

    @Test
    @DisplayName("没收录的专有名词保留原文，只翻类型词")
    void keepsProperNouns() {
        assertThat(PlaceNames.zh("Egerton Salt Mines")).isEqualTo("Egerton盐矿");
        assertThat(PlaceNames.zh("Rothstep")).isEqualTo("Rothstep");
        assertThat(PlaceNames.zh("\"Blue Goblet\" Tavern")).isEqualTo("\"Blue Goblet\"酒馆");
    }

    @Test
    @DisplayName("区域名：已核对的有中文，没收录的保留原文")
    void regionNames() {
        assertThat(PlaceNames.regionZh("Springlands")).isEqualTo("春之原野");
        assertThat(PlaceNames.regionZh("Nomad Highlands")).isEqualTo("游牧高地");
        assertThat(PlaceNames.regionZh("Cinder's Cradle")).isEqualTo("Cinder's Cradle");
        assertThat(PlaceNames.regionZh("")).isEmpty();
    }

    @Test
    @DisplayName("空值与纯中文不抛异常")
    void nullAndChinese() {
        assertThat(PlaceNames.zh(null)).isNull();
        assertThat(PlaceNames.zh("")).isEmpty();
    }
}
