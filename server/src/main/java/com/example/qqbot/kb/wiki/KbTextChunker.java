package com.example.qqbot.kb.wiki;

import java.util.ArrayList;
import java.util.List;

/**
 * 把清洗后的正文切成若干块 —— **在段落/句子边界切，不做硬截断**。
 *
 * <h2>为什么需要它（2026-10-02 补）</h2>
 * 原先 {@link WikiArticleImporter} 对超长正文直接 {@code substring(0, maxBodyChars)}。
 * 而配置注释里写着「机制页实测约 11KB」、单块上限 1.5KB —— <b>86% 的正文被丢掉</b>，
 * 关键信息（前置条件、数值、做法）很可能正好在被截掉的部分。
 * 这与实测缺口「任务/机制的中文检索只有 2/4」高度吻合。
 *
 * <h2>为什么是"切块"而不是"把上限调大"</h2>
 * 调大上限会让单块变长：稀释语义、撑大 prompt（top-5 × 单块长度）。
 * 切成多块之后<b>每块仍然短</b>，检索命中的是"讲这件事的那一段" ——
 * 比"一整篇的向量"更准，而且一块都不丢。
 *
 * <h2>切法（从粗到细，三级兜底）</h2>
 * <ol>
 *   <li>按行拆（清洗后一段一行），去掉空行；</li>
 *   <li>贪心地往当前块里塞，塞不下就落块 —— <b>不会从句子中间断开</b>；</li>
 *   <li>遇到"单段就超限"的（比如一整篇没有换行），再按句末标点 {@code 。！？；!?;} 拆；</li>
 *   <li>连一句都超限才按字符硬切 —— 最后的兜底，正常语料走不到。</li>
 * </ol>
 *
 * <p><b>不丢字</b>：切出来的块拼回去（忽略首尾空白与分块时加的分隔换行）与原文一致。
 * {@code KbTextChunkerTest} 盯着这条性质 —— 它是这次改动的**唯一目的**。
 */
public final class KbTextChunker {

    /** 句末标点（中英）。切超长段时在这些字符**之后**断开 */
    private static final String SENTENCE_END = "。！？；!?;";

    private KbTextChunker() {
    }

    /**
     * @param body     清洗后的正文（可为 null / 空 / 全空白）
     * @param maxChars 单块上限（字符）。{@code <= 0} 表示不切
     * @return 至少一块；正文为空时返回**空列表**（调用方据此判断"这页没有可读正文"）
     */
    public static List<String> split(String body, int maxChars) {
        List<String> out = new ArrayList<>();
        if (body == null || body.isBlank()) {
            return out;
        }
        String text = body.strip();
        if (maxChars <= 0 || text.length() <= maxChars) {
            out.add(text);
            return out;
        }
        StringBuilder cur = new StringBuilder();
        for (String para : lines(text)) {
            if (para.length() > maxChars) {
                flush(out, cur);
                out.addAll(splitLongParagraph(para, maxChars));
                continue;
            }
            if (cur.length() > 0 && cur.length() + 1 + para.length() > maxChars) {
                flush(out, cur);
            }
            if (cur.length() > 0) {
                cur.append('\n');
            }
            cur.append(para);
        }
        flush(out, cur);
        return out;
    }

    /** 按行拆，去掉空行与首尾空白 */
    private static List<String> lines(String text) {
        List<String> out = new ArrayList<>();
        for (String line : text.split("\n")) {
            String t = line.strip();
            if (!t.isEmpty()) {
                out.add(t);
            }
        }
        if (out.isEmpty()) {
            out.add(text.strip());
        }
        return out;
    }

    /** 单段超限：先按句末标点拆，再贪心合并；仍超限才硬切 */
    private static List<String> splitLongParagraph(String para, int maxChars) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (String sentence : sentences(para)) {
            if (sentence.length() > maxChars) {
                flush(out, cur);
                for (int i = 0; i < sentence.length(); i += maxChars) {
                    out.add(sentence.substring(i, Math.min(sentence.length(), i + maxChars)));
                }
                continue;
            }
            if (cur.length() > 0 && cur.length() + sentence.length() > maxChars) {
                flush(out, cur);
            }
            cur.append(sentence);
        }
        flush(out, cur);
        return out;
    }

    /** 按句末标点切成"带着标点"的句子 */
    private static List<String> sentences(String para) {
        List<String> out = new ArrayList<>();
        int start = 0;
        for (int i = 0; i < para.length(); i++) {
            if (SENTENCE_END.indexOf(para.charAt(i)) >= 0) {
                out.add(para.substring(start, i + 1));
                start = i + 1;
            }
        }
        if (start < para.length()) {
            out.add(para.substring(start));
        }
        return out;
    }

    private static void flush(List<String> out, StringBuilder cur) {
        if (cur.length() > 0) {
            out.add(cur.toString());
            cur.setLength(0);
        }
    }
}
