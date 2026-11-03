package com.example.qqbot.eval;

import com.example.qqbot.trace.KbTrace;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 逐句引用映射 —— 把回答拆成句子，看每一句用到了哪几块检索资料。
 *
 * <p>这是**字符级归属**，不是语义校验：句子出现了某块资料标题，就记一句"可能来自这块"。
 * 它回答的是"回答有没有引用检索结果、引用覆盖多少句"，不是"引用得对不对"。
 * 真正的引用正确性要靠模型标注与人工复核，这里不假装能做到。
 *
 * <p>没有标题的块不参与匹配 —— 用正文猜归属一定会误判。
 */
public final class CitationExtractor {

    /** 按中文/英文句读与换行切句，保留句子原文给页面展示 */
    private static final Pattern SPLIT = Pattern.compile("(?<=[。！？!?；;\n])");

    private CitationExtractor() {
    }

    public record Line(int index, String sentence, List<String> blockIds) {
    }

    public static List<Line> extract(String answer, List<KbTrace.Hit> hits) {
        if (answer == null || answer.isBlank() || hits == null || hits.isEmpty()) {
            return List.of();
        }
        List<Line> out = new ArrayList<>();
        int index = 0;
        for (String raw : SPLIT.split(answer)) {
            String sentence = raw.strip();
            if (sentence.isEmpty()) {
                continue;
            }
            Set<String> ids = new LinkedHashSet<>();
            for (KbTrace.Hit hit : hits) {
                if (hit.title() != null && !hit.title().isBlank()
                        && sentence.contains(hit.title())) {
                    ids.add(hit.id());
                }
            }
            out.add(new Line(index++, sentence, List.copyOf(ids)));
        }
        return out;
    }

    /** 至少命中一块资料的句子数 */
    public static int supported(List<Line> lines) {
        return (int) lines.stream().filter(l -> !l.blockIds().isEmpty()).count();
    }
}
