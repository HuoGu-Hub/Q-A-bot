package com.example.qqbot.distill;

import com.example.qqbot.persistence.DistillNoteRepository;

import java.util.List;
import java.util.Locale;

/**
 * 蒸馏笔记的近似去重。
 *
 * <p>精确 title_norm / body_hash 只能挡住完全相同的文本；同一事实被不同批次
 * 重新组织后，标题会多一个“位置”“攻略”或少一个“3级”，正文也会换措辞。
 * 这里用标题字符二元组相似度 + 最长公共主题词 + 正文相似度做兜底。
 */
final class DistillDeduper {

    private DistillDeduper() {
    }

    static DistillNoteRepository.Note find(List<DistillNoteRepository.Note> notes,
                                           String title, String body) {
        if (notes == null || notes.isEmpty()) {
            return null;
        }
        for (DistillNoteRepository.Note note : notes) {
            if (isDuplicate(note.title(), note.body(), title, body)) {
                return note;
            }
        }
        return null;
    }

    static boolean isDuplicate(String existingTitle, String existingBody,
                               String candidateTitle, String candidateBody) {
        String a = norm(existingTitle);
        String b = norm(candidateTitle);
        if (a.isEmpty() || b.isEmpty()) {
            return false;
        }
        double titleSim = similarity(a, b);
        double bodySim = similarity(norm(existingBody), norm(candidateBody));
        if (titleSim >= 0.70) {
            return bodySim >= 0.18;
        }
        int common = longestCommonSubstring(a, b);
        double ratio = (double) common / Math.max(1, Math.min(a.length(), b.length()));
        if (common < 4 || ratio < 0.30) {
            return false;
        }
        return bodySim >= 0.18;
    }

    /** 合并正文：保留旧内容，只追加新内容里没有出现过的行 */
    static String mergeBody(String oldBody, String newBody) {
        String oldText = oldBody == null ? "" : oldBody.strip();
        String newText = newBody == null ? "" : newBody.strip();
        if (oldText.isEmpty()) {
            return newText;
        }
        if (newText.isEmpty()) {
            return oldText;
        }
        String oldNorm = norm(oldText);
        String newNorm = norm(newText);
        if (oldNorm.contains(newNorm)) {
            return oldText;
        }
        if (newNorm.contains(oldNorm)) {
            return newText;
        }

        java.util.List<String> out = new java.util.ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (String line : oldText.split("\\R")) {
            String clean = line.strip();
            if (!clean.isEmpty()) {
                out.add(clean);
                seen.add(norm(clean));
            }
        }
        for (String line : newText.split("\\R")) {
            String clean = line.strip();
            String n = norm(clean);
            if (!n.isEmpty() && !seen.contains(n) && !oldNorm.contains(n)) {
                out.add(clean);
                seen.add(n);
            }
        }
        return String.join("\n", out);
    }

    /** 别名去重合并，保留顺序 */
    static String mergeAliases(String oldAliases, String newAliases) {
        java.util.LinkedHashSet<String> out = new java.util.LinkedHashSet<>();
        for (String source : new String[]{oldAliases, newAliases}) {
            if (source == null) {
                continue;
            }
            for (String part : source.split("[、,，;；\\s]+")) {
                if (!part.isBlank()) {
                    out.add(part.strip());
                }
            }
        }
        return String.join("、", out);
    }

    static String norm(String s) {
        if (s == null) {
            return "";
        }
        return s.toLowerCase(Locale.ROOT)
                .replaceAll("[\\s\\p{Punct}，。！？、；：“”‘’（）【】《》]", "");
    }

    static double similarity(String a, String b) {
        if (a.equals(b)) {
            return 1;
        }
        if (a.length() < 2 || b.length() < 2) {
            return 0;
        }
        var left = grams(a);
        var right = grams(b);
        int common = 0;
        for (String g : left) {
            if (right.contains(g)) {
                common++;
            }
        }
        return (double) common / Math.max(1, left.size() + right.size() - common);
    }

    private static java.util.Set<String> grams(String s) {
        java.util.Set<String> out = new java.util.HashSet<>();
        for (int i = 0; i + 1 < s.length(); i++) {
            out.add(s.substring(i, i + 2));
        }
        return out;
    }

    static int longestCommonSubstring(String a, String b) {
        int best = 0;
        int[] prev = new int[b.length() + 1];
        for (int i = 1; i <= a.length(); i++) {
            int[] cur = new int[b.length() + 1];
            for (int j = 1; j <= b.length(); j++) {
                if (a.charAt(i - 1) == b.charAt(j - 1)) {
                    cur[j] = prev[j - 1] + 1;
                    best = Math.max(best, cur[j]);
                }
            }
            prev = cur;
        }
        return best;
    }
}
