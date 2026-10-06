package com.example.qqbot.kb.term;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 分隔符文本解析 —— 这里错一次，**整份词条表都会错，而且不报错**。
 *
 * <h2>真实故障（2026-10-04 实测）</h2>
 * 原来的 {@code parse} 里，字段中间的 {@code "} 也会开启引号模式。
 * 按 RFC4180，双引号**只在字段开头**才特殊。后果不是"丢一个引号"那么轻：
 * 一个游离的引号会把后面的**制表符和换行一起吞掉** ——
 * 用户在表格里打错一个引号，整份导入就全乱，而且全程不报错。
 */
class CsvTableQuoteTest {

    @Test
    @DisplayName("★ 字段中间的引号是普通字符，不能开启引号模式")
    void midFieldQuoteIsLiteral() {
        List<List<String>> rows = CsvTable.parse("a\t带\"引号\"的名字\tverified\n", '\t');
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0))
                .as("引号要原样保留，不能被吞掉")
                .containsExactly("a", "带\"引号\"的名字", "verified");
    }

    @Test
    @DisplayName("★ 游离引号不能吞掉后面的制表符和换行（原来会）")
    void strayQuoteDoesNotSwallowRest() {
        List<List<String>> rows = CsvTable.parse("a\t带\"引号\tverified\nb\t狼\tverified\n", '\t');
        assertThat(rows)
                .as("两行必须是两行 —— 不能被一个引号合成一行")
                .hasSize(2);
        assertThat(rows.get(1)).containsExactly("b", "狼", "verified");
    }

    @Test
    @DisplayName("正常转义仍然正确：字段开头的引号开启引号模式，\"\" 是转义")
    void properEscapingStillWorks() {
        List<List<String>> rows =
                CsvTable.parse("a,\"含,逗号\",\"带\"\"引号\"\"的\"\n", ',');
        assertThat(rows.get(0))
                .containsExactly("a", "含,逗号", "带\"引号\"的");
    }

    @Test
    @DisplayName("引号内的换行仍然算同一个字段")
    void newlineInsideQuotes() {
        List<List<String>> rows = CsvTable.parse("a,\"第一行\n第二行\"\n", ',');
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get(1)).isEqualTo("第一行\n第二行");
    }

    @Test
    @DisplayName("空字段与连续分隔符")
    void emptyCells() {
        List<List<String>> rows = CsvTable.parse("a\t\t\tverified\n", '\t');
        assertThat(rows.get(0)).containsExactly("a", "", "", "verified");
    }
}
