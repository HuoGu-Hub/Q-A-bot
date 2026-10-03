package com.example.qqbot.kb.wiki;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link KbTextChunker} 的行为。
 *
 * <p>它替代的是原来那句 {@code body.substring(0, maxBodyChars)} ——
 * 所以这里最重要的不是"切得漂亮"，而是 <b>一个字都没丢</b>。
 */
class KbTextChunkerTest {

    /** 去掉所有空白，用来比对"有没有丢字" */
    private static String squeeze(String s) {
        return s == null ? "" : s.replaceAll("\\s+", "");
    }

    @Test
    @DisplayName("短正文原样返回一块 —— 不引入任何变化（存量页行为不变）")
    void shortBodyIsOneChunk() {
        String body = "钢扶手（Steel Handrail）\n用于设计和美化居所的装饰物件。\n材料：钢锭 x4";
        List<String> chunks = KbTextChunker.split(body, 1500);
        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0)).isEqualTo(body);
    }

    @Test
    @DisplayName("空 / null / 全空白 → 空列表（调用方据此跳过建块）")
    void blankBodyIsEmpty() {
        assertThat(KbTextChunker.split(null, 100)).isEmpty();
        assertThat(KbTextChunker.split("", 100)).isEmpty();
        assertThat(KbTextChunker.split("   \n  \n ", 100)).isEmpty();
    }

    @Test
    @DisplayName("maxChars <= 0 表示不切")
    void zeroLimitMeansNoSplit() {
        String body = "一".repeat(5000);
        assertThat(KbTextChunker.split(body, 0)).hasSize(1);
        assertThat(KbTextChunker.split(body, -1)).hasSize(1);
    }

    @Test
    @DisplayName("★ 超长正文一块都不丢 —— 拼回去（忽略空白）与原文一致")
    void losesNothing() {
        StringBuilder src = new StringBuilder();
        for (int i = 0; i < 400; i++) {
            src.append("第 ").append(i).append(" 条机制说明：这一段讲的是某个具体规则与它的数值。\n");
        }
        String body = src.toString();
        assertThat(body.length()).isGreaterThan(1500);   // 确认真的超限了

        List<String> chunks = KbTextChunker.split(body, 1500);
        assertThat(chunks).hasSizeGreaterThan(1);
        assertThat(squeeze(String.join("", chunks))).isEqualTo(squeeze(body));
    }

    @Test
    @DisplayName("★ 关键信息在**文末**也不会丢 —— 这正是旧实现 substring 的真实故障")
    void tailIsNotLost() {
        String head = "普通说明。\n".repeat(600);          // 远超 1500 字
        String tail = "关键：必须先在灵火祭坛复活才能进入该区域。";
        List<String> chunks = KbTextChunker.split(head + tail, 1500);

        assertThat(chunks).hasSizeGreaterThan(1);
        assertThat(chunks.get(chunks.size() - 1)).contains("必须先在灵火祭坛复活");
        // 旧实现：body.substring(0,1500) —— 这句必然不在任何一块里
        assertThat(String.join("", chunks)).contains("必须先在灵火祭坛复活");
    }

    @Test
    @DisplayName("每块都不超过上限")
    void everyChunkWithinLimit() {
        StringBuilder src = new StringBuilder();
        for (int i = 0; i < 200; i++) {
            src.append("段落 ").append(i).append("：").append("内容".repeat(30)).append("\n");
        }
        for (String c : KbTextChunker.split(src.toString(), 800)) {
            assertThat(c.length()).isLessThanOrEqualTo(800);
        }
    }

    @Test
    @DisplayName("在段落边界切 —— 不从句子中间断开")
    void splitsAtParagraphBoundaries() {
        String body = "第一段完整的话。\n第二段完整的话。\n第三段完整的话。\n第四段完整的话。";
        List<String> chunks = KbTextChunker.split(body, 20);
        // 每块都应是"完整的若干行"，不能出现半句话
        for (String c : chunks) {
            for (String line : c.split("\\n")) {
                assertThat(body).contains(line);
            }
        }
    }

    @Test
    @DisplayName("一整段没有换行 → 退到按句末标点切（仍然不丢字）")
    void splitsLongParagraphAtSentences() {
        String body = "甲。乙！丙？丁；戊。".repeat(60);   // 一整段，无换行
        List<String> chunks = KbTextChunker.split(body, 50);
        assertThat(chunks).hasSizeGreaterThan(1);
        assertThat(squeeze(String.join("", chunks))).isEqualTo(squeeze(body));
    }

    @Test
    @DisplayName("连一句都超限才硬切（兜底路径，也不丢字）")
    void hardSplitAsLastResort() {
        String body = "一".repeat(500);   // 没有标点、没有换行
        List<String> chunks = KbTextChunker.split(body, 100);
        assertThat(chunks).hasSize(5);
        assertThat(String.join("", chunks)).isEqualTo(body);
        for (String c : chunks) {
            assertThat(c.length()).isLessThanOrEqualTo(100);
        }
    }

    @Test
    @DisplayName("刚好等于上限 → 一块（边界不提前切）")
    void exactlyAtLimitIsOneChunk() {
        String body = "甲".repeat(100);
        assertThat(KbTextChunker.split(body, 100)).hasSize(1);
    }
}
