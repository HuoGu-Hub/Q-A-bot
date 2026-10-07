package com.example.qqbot.kb.wiki;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 块 id 撞车检测（2026-10-07 实测发现，先"认出来"再说）。
 *
 * <p>{@code blockIdOf} 是有损映射（标点→短横、大小写归一），所以**两个不同的上游页
 * 可能共享同一个块 id**：后导入的会把先导入的整块内容**覆盖掉**，而两页的状态行都报成功。
 * 实测两组：{@code Quests/Farmer's Advice} / {@code Quests/Farmer’s Advice}（直引号 vs 弯引号），
 * {@code Crash Down Force} / {@code Crash Down: Force}。
 */
class WikiArticleImporterIdCollisionTest {

    @Test
    @DisplayName("★ 撞车要能认出来；同一页重复出现不算撞车")
    void detectsCollision() {
        Map<String, String> seen = new LinkedHashMap<>();

        assertThat(WikiArticleImporter.registerBlockId(seen, "wiki-crash-down-force", "Crash Down Force"))
                .as("第一次占用：没人占过")
                .isNull();
        assertThat(WikiArticleImporter.registerBlockId(seen, "wiki-crash-down-force", "Crash Down: Force"))
                .as("第二个页抢同一个 id：必须报出是谁先占的")
                .isEqualTo("Crash Down Force");
        assertThat(WikiArticleImporter.registerBlockId(seen, "wiki-crash-down-force", "Crash Down Force"))
                .as("同一页（同页出现在多个来源里）重复登记不算撞车")
                .isNull();

        // 结论：这两组真实页名确实映射到同一个 slug（用真实值固定证据）
        assertThat(WikiArticleImporter.blockIdOf("Crash Down Force"))
                .isEqualTo(WikiArticleImporter.blockIdOf("Crash Down: Force"));
        assertThat(WikiArticleImporter.blockIdOf("Quests/Farmer's Advice"))
                .isEqualTo(WikiArticleImporter.blockIdOf("Quests/Farmer\u2019s Advice"));
    }
}