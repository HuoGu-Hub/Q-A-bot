package com.example.qqbot.kb.wiki;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 中英对照索引验收 —— 纯函数，不联网。
 *
 * <p>这里锁住的核心是**"查不到就返回 null"**：宁可留英文，也不要编一个中文名。
 * 编错了会污染整个检索，而且比缺失更难发现。
 */
class NameZhIndexTest {

    private static final List<String> CORPUS_TITLES = List.of(
            "蜂巢喷烟器（Beehive Smoker）",
            "蜂巢（Beehive）",
            "独眼巨人头骨（Cyclops Skull）",
            "独眼巨人战利品（Cyclops Trophy）",
            "青铜独眼巨人战利品（Bronze Cyclops Trophy）",
            "植物与幼苗年鉴（Almanac of Plants and Seedlings）",
            "铁匠（Blacksmith）",
            "不是标准形态的标题",
            "空括号（）");

    @Test
    @DisplayName("索引只认「中文（English）」形态，畸形标题被忽略")
    void buildIndex() {
        NameZhIndex idx = NameZhIndex.fromTitles(CORPUS_TITLES);
        // 9 条里 7 条是标准形态：
        //   被丢的两条是 "不是标准形态的标题"（没有括号）与 "空括号（）"（英文名为空）
        assertThat(idx.size()).isEqualTo(7);
    }

    @Test
    @DisplayName("★ 最长前缀匹配：A Beehive Smoker -> 蜂巢喷烟器（去掉冠词后命中）")
    void longestPrefix() {
        NameZhIndex idx = NameZhIndex.fromTitles(CORPUS_TITLES);
        assertThat(idx.resolve("A Beehive Smoker")).isEqualTo("蜂巢喷烟器");
        assertThat(idx.resolve("Beehive Smoker")).isEqualTo("蜂巢喷烟器");
        // 多词前缀：整条命中
        assertThat(idx.resolve("Almanac of Plants and Seedlings For The Farmer")).isEqualTo("植物与幼苗年鉴");
    }

    @Test
    @DisplayName("★ 头词共同前缀：Cyclops 靠 3 条同头词条的中文共同前缀推出来")
    void headWordCommonPrefix() {
        NameZhIndex idx = NameZhIndex.fromTitles(CORPUS_TITLES);
        // 语料里没有单独的 Cyclops，但有 Cyclops Skull / Cyclops Trophy
        // 中文名共同前缀是「独眼巨人」
        assertThat(idx.resolve("Cyclops")).isEqualTo("独眼巨人");
    }

    @Test
    @DisplayName("★ 查不到就返回 null —— 不编（Combat Mechanics 语料里没有译名）")
    void unknownStaysNull() {
        NameZhIndex idx = NameZhIndex.fromTitles(CORPUS_TITLES);
        assertThat(idx.resolve("Combat Mechanics")).isNull();
        assertThat(idx.resolve("Crafting")).isNull();
        assertThat(idx.resolve("Survival")).isNull();
        assertThat(idx.resolve("")).isNull();
        assertThat(idx.resolve(null)).isNull();
    }

    @Test
    @DisplayName("头词只有一条时不猜（避免把单个词条的名字当成通名）")
    void singleHeadWordIsNotEnough() {
        NameZhIndex idx = NameZhIndex.fromTitles(List.of("铁匠（Blacksmith）"));
        assertThat(idx.resolve("Blacksmith")).isEqualTo("铁匠");
        // 换一个只有一条同头词的词：不该推出一个"共同前缀"
        assertThat(idx.resolve("Blacksmiths")).isNull();
    }

    @Test
    @DisplayName("去冠词")
    void stripArticle() {
        assertThat(NameZhIndex.stripArticle("A Beehive Smoker")).isEqualTo("Beehive Smoker");
        assertThat(NameZhIndex.stripArticle("An Alchemist")).isEqualTo("Alchemist");
        assertThat(NameZhIndex.stripArticle("The Farmer")).isEqualTo("Farmer");
        assertThat(NameZhIndex.stripArticle("Cyclops")).isEqualTo("Cyclops");
    }
}
