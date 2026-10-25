package com.example.qqbot.distill;

import com.example.qqbot.persistence.ChatRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 聊天文件导入。
 *
 * <p>第一版支持 JSONL 与常见 TXT 两种格式。解析不了的行进错误清单，
 * 不静默丢；导入结果里能看到"读了几行、成了几条、错了几行"。
 *
 * <p>写库统一走 {@link ChatRepository} 的 {@code raw_hash} 去重，同一份文件重复导入不会写两次。
 */
@Service
public class ChatImportService {

    private static final Logger log = LoggerFactory.getLogger(ChatImportService.class);

    /** TXT 的常见行格式：[时间] 昵称: 内容 */
    private static final Pattern TXT_LINE = Pattern.compile(
            "^\\s*\\[?(\\d{4}[-/]\\d{1,2}[-/]\\d{1,2}[ T]\\d{1,2}:\\d{2}(?::\\d{2})?)\\]?\\s+(.+?)\\s*[:：]\\s*(.*)$");

    public record Parsed(String ts, long groupId, String sender, String text, int imageCount) {
    }

    public record ParseResult(String format, List<Parsed> messages, List<String> errors, int lines) {
    }

    public record Preview(String filename, String format, int lines, int parsed, int errors,
                          List<String> samples, List<String> errorSamples) {
    }

    public record ImportResult(long importId, String format, int parsed, int inserted,
                               int duplicates, int errors) {
    }

    private final ChatRepository repo;
    private final DistillPolicy props;
    private final ObjectMapper mapper;

    public ChatImportService(ChatRepository repo, DistillPolicy props, ObjectMapper mapper) {
        this.repo = repo;
        this.props = props;
        this.mapper = mapper;
    }

    public Preview preview(String filename, String content) {
        ParseResult r = parse(filename, content);
        List<String> samples = new ArrayList<>();
        for (int i = 0; i < Math.min(3, r.messages().size()); i++) {
            Parsed m = r.messages().get(i);
            String text = m.text().length() > 60 ? m.text().substring(0, 60) + "…" : m.text();
            samples.add(m.ts() + " " + m.sender() + ": " + text);
        }
        List<String> errs = r.errors().size() > 5 ? r.errors().subList(0, 5) : r.errors();
        return new Preview(filename, r.format(), r.lines(), r.messages().size(),
                r.errors().size(), samples, errs);
    }

    public ImportResult importFile(String filename, String content, long defaultGroupId) {
        ParseResult parsed = parse(filename, content);
        if (parsed.messages().isEmpty()) {
            throw new IllegalArgumentException("没有解析出任何消息" + firstError(parsed));
        }
        String now = Instant.now().toString();
        long importId = repo.insertImport(filename, parsed.format(), defaultGroupId,
                parsed.messages().size(), "importing", "", now);
        if (importId <= 0) {
            throw new IllegalStateException("写入导入记录失败");
        }
        int inserted = 0;
        int duplicates = 0;
        try {
            for (Parsed m : parsed.messages()) {
                long group = m.groupId() > 0 ? m.groupId() : defaultGroupId;
                ChatRepository.Inserted ins = repo.insertMessageIfAbsent(new ChatRepository.NewMessage(
                        "file", importId, group, 0, m.ts(), hash("sender:" + group + ":" + m.sender()),
                        m.sender(), m.text(), m.imageCount(), rawHash(m, group), now));
                if (ins.created()) {
                    inserted++;
                } else {
                    duplicates++;
                }
            }
            String status = parsed.errors().isEmpty() ? "done" : "partial";
            repo.updateImport(importId, inserted, status, firstError(parsed));
            log.info("[DISTILL] 导入 {}：{} 行，解析 {}，写入 {}，重复 {}，错误 {}",
                    filename, parsed.lines(), parsed.messages().size(), inserted, duplicates,
                    parsed.errors().size());
            return new ImportResult(importId, parsed.format(), parsed.messages().size(),
                    inserted, duplicates, parsed.errors().size());
        } catch (RuntimeException e) {
            repo.updateImport(importId, inserted, "failed", String.valueOf(e.getMessage()));
            throw e;
        }
    }

    /**
     * 解析文件。公开给测试用，不写库。
     */
    public ParseResult parse(String filename, String content) {
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("文件内容为空");
        }
        if (content.length() > Math.max(1, props.getMaxImportChars())) {
            throw new IllegalArgumentException("文件太大，超过 " + props.getMaxImportChars() + " 字符");
        }
        String format = detectFormat(filename, content);
        return "jsonl".equals(format) ? parseJsonl(content) : parseTxt(content);
    }

    private ParseResult parseJsonl(String content) {
        List<Parsed> out = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        String[] lines = content.replace("\uFEFF", "").split("\\R", -1);
        int lineNo = 0;
        for (String line : lines) {
            lineNo++;
            String s = line.trim();
            if (s.isEmpty()) {
                continue;
            }
            try {
                JsonNode n = mapper.readTree(s);
                String tsRaw = firstText(n, "ts", "time", "timestamp", "date", "datetime");
                Instant ts = ChatTime.parse(tsRaw);
                if (ts == null) {
                    errors.add("第 " + lineNo + " 行：时间无法解析 " + shortOf(tsRaw));
                    continue;
                }
                String text = firstText(n, "text", "content", "message", "msg", "body");
                int images = imageCount(n);
                if ((text == null || text.isBlank()) && images == 0) {
                    errors.add("第 " + lineNo + " 行：没有文字也没有图片");
                    continue;
                }
                String sender = firstText(n, "sender", "nickname", "name", "user", "qq");
                long group = firstLong(n, "group_id", "groupId", "group");
                out.add(new Parsed(ts.toString(), group, sender == null ? "" : sender,
                        text == null ? "" : text.trim(), images));
            } catch (Exception e) {
                errors.add("第 " + lineNo + " 行：JSON 解析失败 " + shortOf(e.getMessage()));
            }
        }
        return new ParseResult("jsonl", out, errors, lineNo);
    }

    private ParseResult parseTxt(String content) {
        List<Parsed> out = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        String[] lines = content.replace("\uFEFF", "").split("\\R", -1);
        int lineNo = 0;
        for (String line : lines) {
            lineNo++;
            String s = line.trim();
            if (s.isEmpty()) {
                continue;
            }
            Matcher m = TXT_LINE.matcher(s);
            if (!m.matches()) {
                errors.add("第 " + lineNo + " 行：不符合「时间 昵称: 内容」格式");
                continue;
            }
            Instant ts = ChatTime.parse(m.group(1));
            if (ts == null) {
                errors.add("第 " + lineNo + " 行：时间无法解析 " + m.group(1));
                continue;
            }
            String sender = m.group(2).trim();
            String text = m.group(3).trim();
            if (text.isEmpty()) {
                errors.add("第 " + lineNo + " 行：内容为空");
                continue;
            }
            out.add(new Parsed(ts.toString(), 0, sender, text, 0));
        }
        return new ParseResult("txt", out, errors, lineNo);
    }

    private static String detectFormat(String filename, String content) {
        String name = filename == null ? "" : filename.toLowerCase(Locale.ROOT);
        if (name.endsWith(".jsonl") || name.endsWith(".ndjson") || name.endsWith(".json")) {
            return "jsonl";
        }
        if (name.endsWith(".txt") || name.endsWith(".log") || name.endsWith(".csv")) {
            return "txt";
        }
        String first = content.stripLeading();
        return first.startsWith("{") ? "jsonl" : "txt";
    }

    private static String firstText(JsonNode n, String... names) {
        for (String name : names) {
            JsonNode v = n.get(name);
            if (v != null && !v.isNull()) {
                if (v.isTextual()) {
                    return v.asText();
                }
                if (v.isNumber()) {
                    return v.asText();
                }
                if (v.isObject()) {
                    String nested = firstText(v, "nickname", "name", "card", "text");
                    if (nested != null) {
                        return nested;
                    }
                }
            }
        }
        return null;
    }

    private static long firstLong(JsonNode n, String... names) {
        for (String name : names) {
            JsonNode v = n.get(name);
            if (v != null && v.isValueNode()) {
                String s = v.asText("");
                try {
                    return Long.parseLong(s.trim());
                } catch (NumberFormatException ignored) {
                    // 继续找下一个字段
                }
            }
        }
        return 0;
    }

    private static int imageCount(JsonNode n) {
        JsonNode images = n.get("images");
        if (images != null && images.isArray()) {
            return images.size();
        }
        JsonNode count = n.get("image_count");
        if (count == null) {
            count = n.get("imageCount");
        }
        return count != null && count.isNumber() ? Math.max(0, count.asInt()) : 0;
    }

    private static String rawHash(Parsed m, long group) {
        return hash(group + "|" + m.ts() + "|" + m.sender() + "|" + m.text() + "|" + m.imageCount());
    }

    private static String hash(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("计算哈希失败", e);
        }
    }

    private static String firstError(ParseResult r) {
        return r.errors().isEmpty() ? "" : "；首条错误：" + r.errors().get(0);
    }

    private static String shortOf(String s) {
        if (s == null) {
            return "";
        }
        return s.length() > 100 ? s.substring(0, 100) + "…" : s;
    }
}
