package com.example.qqbot.distill;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DistillDeduperTest {

    @Test
    @DisplayName("同一事实换标题、换措辞仍然判为重复")
    void detectsRewordedDuplicate() {
        String bodyA = "在初始出生点第一个任务附近，山崖上有一个3级金色宝箱，"
                + "打开后可以获得一件金色装备和一件稀有物品。";
        String bodyB = "出生点第一个任务山崖上固定刷新3级金色宝箱，"
                + "里面大概一件金色装备、一件稀有装备。";
        assertThat(DistillDeduper.isDuplicate(
                "出生点第一个任务山崖上的3级金色宝箱", bodyA,
                "出生点第一个任务山崖的金色宝箱", bodyB)).isTrue();
    }

    @Test
    @DisplayName("标题相似但正文讲的是不同机制时不合并")
    void keepsDifferentFacts() {
        String bodyA = "灵火祭坛在东边，需要三个树脂才能点燃。";
        String bodyB = "火晶可以在北边矿洞采集，每次刷新两个。";
        assertThat(DistillDeduper.isDuplicate(
                "灵火祭坛位置", bodyA,
                "灵火祭坛材料", bodyB)).isFalse();
    }

    @Test
    @DisplayName("合并正文时保留旧内容，只追加新行")
    void mergesBodyLines() {
        String oldBody = "1. 山崖上有3级金色宝箱\n2. 打开后有一件金色装备";
        String newBody = "1. 山崖上有3级金色宝箱\n2. 打开后有一件金色装备\n3. 还会掉一件稀有物品";
        String merged = DistillDeduper.mergeBody(oldBody, newBody);
        assertThat(merged).contains("3级金色宝箱");
        assertThat(merged).contains("金色装备");
        assertThat(merged).contains("稀有物品");
        assertThat(merged.lines().count()).isEqualTo(3);
        assertThat(DistillDeduper.mergeAliases("金箱、宝箱", "宝箱、三级箱"))
                .isEqualTo("金箱、宝箱、三级箱");
    }

    @Test
    @DisplayName("完全不同的主题不合并")
    void keepsUnrelated() {
        assertThat(DistillDeduper.isDuplicate(
                "灵火祭坛位置", "灵火祭坛在东边，需要三个树脂。",
                "母亲戒指获取", "沿着出生点道路一直走，在山洞箱子里拿到戒指。")).isFalse();
    }
}
