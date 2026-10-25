package com.example.qqbot.distill;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 把模型返回的 JSON 变成可入库的笔记草稿。
 *
 * <p>模型经常把 JSON 包在 markdown 里，或者多写一段解释 —— 先抠出第一个花括号块。
 * 校验失败的单条笔记直接跳过并计数，不让一条坏数据拖垮整批。
 */
@Component
public class DistillResponseParser {

    private static final Logger log = LoggerFactory.getLogger(DistillResponseParser.class);

    /** 明显是联系方式或外链的内容不进知识库 */
    private static final Pattern PII = Pattern.compile(
            "(?i)(https?://|www\\.|@\\S+|\\b1[3-9]\\d{9}\\b|[\\w.+-]+@[\\w.-]+\\.[A-Za-z]{2,})");

    private final ObjectMapper mapper;

    public DistillResponseParser(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * @param allowedMessages 本批次允许引用的消息 id
     * @param allowedImages   本批次允许引用的图片 id
     * @param knownCategories 现有分类
     * @param maxNotes        最多保留几条
     */
    public List<DistillDraft> parse(String reply, Set<Long> allowedMessages, Set<Long> allowedImages,
                                    Set<String> knownCategories, int maxNotes) {
        if (reply == null || reply.isBlank()) {
            throw new IllegalArgumentException("模型返回为空");
        }
        String json = extractJson(reply);
        if (json == null) {
            throw new IllegalArgumentException("模型回复里没有 JSON：" + shortOf(reply));
        }
        JsonNode root;
        try {
            root = mapper.readTree(json);
        } catch (Exception e) {
            throw new IllegalArgumentException("模型返回的 JSON 解析失败：" + shortOf(json));
        }
        JsonNode notes = root.path("notes");
        if (!notes.isArray()) {
            throw new IllegalArgumentException("模型返回缺少 notes 数组：" + shortOf(json));
        }
        List<DistillDraft> out = new ArrayList<>();
        Set<String> seenTitles = new HashSet<>();
        Set<String> seenBodies = new HashSet<>();
        for (JsonNode n : notes) {
            if (out.size() >= Math.max(1, maxNotes)) {
                break;
            }
            try {
                DistillDraft d = one(n, allowedMessages, allowedImages, knownCategories);
                if (d == null) {
                    continue;
                }
                if (!seenTitles.add(d.titleNorm()) || !seenBodies.add(d.bodyHash())) {
                    continue;
                }
                out.add(d);
            } catch (Exception e) {
                log.debug("[DISTILL] 丢弃一条不合格笔记：{}", e.getMessage());
            }
        }
        return out;
    }

    private DistillDraft one(JsonNode n, Set<Long> allowedMessages, Set<Long> allowedImages,
                             Set<String> knownCategories) {
        String title = text(n, "title").strip();
        String body = text(n, "body").strip();
        if (title.length() < 4 || title.length() > 40) {
            return null;
        }
        if (body.length() < 30 || body.length() > 1600) {
            return null;
        }
        if (PII.matcher(title).find() || PII.matcher(body).find()) {
            return null;
        }
        List<Long> sources = ids(n.get("sourceMessageIds"), allowedMessages);
        if (sources.isEmpty()) {
            return null;
        }
        List<Long> images = ids(n.get("imageIds"), allowedImages);
        String category = text(n, "category").strip();
        if (knownCategories != null && !knownCategories.contains(category)) {
            category = "大佬攻略";
        }
        double confidence = n.path("confidence").asDouble(0);
        if (confidence < 0 || confidence > 1) {
            confidence = Math.max(0, Math.min(1, confidence));
        }
        String aliases = aliases(n.get("aliases"));
        return new DistillDraft(title, normTitle(title), aliases, category, body,
                hash(normalizeBody(body)), confidence, sources, images);
    }

    private static List<Long> ids(JsonNode arr, Set<Long> allowed) {
        if (arr == null || !arr.isArray()) {
            return List.of();
        }
        Set<Long> out = new LinkedHashSet<>();
        for (JsonNode v : arr) {
            long id = v.isNumber() ? v.asLong() : parseLong(v.asText(""));
            if (id > 0 && (allowed == null || allowed.contains(id))) {
                out.add(id);
            }
        }
        return List.copyOf(out);
    }

    private static String aliases(JsonNode node) {
        if (node == null || node.isNull()) {
            return "";
        }
        List<String> out = new ArrayList<>();
        if (node.isArray()) {
            for (JsonNode v : node) {
                String s = v.asText("").strip();
                if (!s.isEmpty() && s.length() <= 20) {
                    out.add(s);
                }
            }
        } else {
            String s = node.asText("").strip();
            if (!s.isEmpty()) {
                out.add(s);
            }
        }
        return String.join("、", out);
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n.get(field);
        return v == null || v.isNull() ? "" : v.asText("");
    }

    private static long parseLong(String s) {
        try {
            return Long.parseLong(s.trim());
        } catch (Exception e) {
            return 0;
        }
    }

    static String normTitle(String title) {
        return title.toLowerCase(Locale.ROOT)
                .replaceAll("[\\s\\p{Punct}，。！？、；：“”‘’（）【】《》]", "");
    }

    static String normalizeBody(String body) {
        return body.replaceAll("\\s+", " ").strip();
    }

    static String hash(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("计算哈希失败", e);
        }
    }

    static String extractJson(String s) {
        int a = s.indexOf('{');
        int b = s.lastIndexOf('}');
        return a >= 0 && b > a ? s.substring(a, b + 1) : null;
    }

    private static String shortOf(String s) {
        String t = s == null ? "" : s.strip().replaceAll("\\s+", " ");
        return t.length() > 160 ? t.substring(0, 160) + "…" : t;
    }
}
