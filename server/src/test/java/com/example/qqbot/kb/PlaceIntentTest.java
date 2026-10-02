package com.example.qqbot.kb;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 聚合意图识别验收 —— 纯函数，不联网。
 *
 * <p>锁住两条：**能认出地点词**，以及**认不出时绝不猜**（返回 null）。
 * 后一条更重要 —— 猜错地点词会把不相关的地点块提到最前，比不修更糟。
 */
class PlaceIntentTest {

    @Test
    @DisplayName("认出聚合型提问，并取出地点词")
    void placeTerms() {
        assertThat(PlaceIntent.isAggregate("空洞大厅有什么")).isTrue();
        assertThat(PlaceIntent.placeTerm("空洞大厅有什么")).isEqualTo("空洞大厅");
        assertThat(PlaceIntent.placeTerm("空洞大厅里有什么")).isEqualTo("空洞大厅");
        assertThat(PlaceIntent.placeTerm("春之原野有什么东西")).isEqualTo("春之原野");
        assertThat(PlaceIntent.placeTerm("启示林都有什么")).isEqualTo("启示林");
    }

    @Test
    @DisplayName("★ 取不到地点词就返回 null —— 不猜")
    void noPlaceTermMeansNoChange() {
        assertThat(PlaceIntent.placeTerm("有什么")).isNull();
        assertThat(PlaceIntent.placeTerm("有啥")).isNull();
        assertThat(PlaceIntent.isAggregate("在哪")).isFalse();
        assertThat(PlaceIntent.placeTerm("珍珠在哪里")).isNull();
        assertThat(PlaceIntent.placeTerm("")).isNull();
        assertThat(PlaceIntent.placeTerm(null)).isNull();
    }

    @Test
    @DisplayName("普通问法不受影响（不是聚合意图）")
    void nonAggregateUntouched() {
        assertThat(PlaceIntent.isAggregate("春之原野在哪")).isFalse();
        assertThat(PlaceIntent.isAggregate("铁匠在哪")).isFalse();
        assertThat(PlaceIntent.isAggregate("蜂箱熏制器任务怎么做")).isFalse();
    }

    @Test
    @DisplayName("只挑地点语料里的块，且不在第一条时才算")
    void findPlaceBlock() {
        List<String> docIds = List.of("物品图鉴", "物品图鉴", "地图·地点", "地图·地点");
        List<String> titles = List.of("空洞大厅展示柜", "空洞大厅方块", "空洞大厅（Hollow Halls）", "启示林（Revelwood）");
        // 第 3 条（下标 2）是地点块且标题含"空洞大厅"
        assertThat(PlaceIntent.findPlaceBlock(docIds, titles, "空洞大厅", "地图·地点")).isEqualTo(2);
        // 地点词对不上任何地点块 -> -1
        assertThat(PlaceIntent.findPlaceBlock(docIds, titles, "不存在的地方", "地图·地点")).isEqualTo(-1);
        // 已经在第一条 -> -1（不用动）
        assertThat(PlaceIntent.findPlaceBlock(List.of("地图·地点"), List.of("空洞大厅（Hollow Halls）"), "空洞大厅", "地图·地点")).isEqualTo(-1);
        // 物品块即使标题含地点词也不算（docId 不是地点语料）
        assertThat(PlaceIntent.findPlaceBlock(List.of("物品图鉴", "物品图鉴"), List.of("x", "空洞大厅展示柜"), "空洞大厅", "地图·地点")).isEqualTo(-1);
    }
}
