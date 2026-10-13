package com.example.qqbot.kb;

import com.example.qqbot.config.PersistenceProperties;
import com.example.qqbot.kb.block.KbBlock;
import com.example.qqbot.kb.block.KbBlockIndex;
import com.example.qqbot.kb.block.KbBlockStore;
import com.example.qqbot.kb.term.KbTermStore;
import com.example.qqbot.persistence.Jdbc;
import com.example.qqbot.persistence.KbBlockRepository;
import com.example.qqbot.persistence.KbTermRepository;
import com.example.qqbot.persistence.SqliteDatabase;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * 一次性运维工具：用大模型给没有中文名的 wiki 词条补别名。
 *
 * <p>确定性规则（NameZhIndex 的派生条目）只能覆盖有派生物品的实体（BOSS / 生物）；
 * 技能、机制、任务这些页在语料里没有任何中文对照，只能让模型给：
 * 官方译名（它可能知道）+ 字面直译 + 玩家口语叫法。
 *
 * <p>可断点续跑：已经有中文名的行直接跳过，中断了再跑一次会接着做。
 *
 * <pre>
 * mvn test -Dtest=TermAliasLlmTool -DfailIfNoTests=false -Dqqbot.tool.confirm=YES -Dqqbot.tool.limit=200
 * </pre>
 *
 * <p>写入状态一律 draft —— 模型给的名字必须人工过一遍，
 * 但检索侧不排除 draft（只有 rejected 才排除），所以补上就能立刻生效。
 */
class TermAliasLlmTool {

    private static final String ENDPOINT = "https://opencode.ai/zen/go/v1/chat/completions";
    private static final String MODEL = System.getProperty("qqbot.tool.model", "deepseek-v4.1-flash");
    /** 一批几条：多了模型会串行、漏答 */
    private static final int BATCH = 8;

    @Test
    @DisplayName("运维工具：大模型补中文别名")
    void generate() throws Exception {
        Assumptions.assumeTrue("YES".equals(System.getProperty("qqbot.tool.confirm")),
                "需要 -Dqqbot.tool.confirm=YES 才动生产库");
        Path repo = findRepoRoot();
        String key = readEnv(repo.resolve(".env"), "OPENCODE_GO_API_KEY");
        Assumptions.assumeTrue(key != null && !key.isEmpty(), "需要 .env 里的 OPENCODE_GO_API_KEY");
        int limit = Integer.parseInt(System.getProperty("qqbot.tool.limit", "200"));

        Path dbPath = Path.of(System.getProperty("qqbot.tool.db",
                repo.resolve("server/data/qa/qqbot.sqlite").toString())).toAbsolutePath();
        PersistenceProperties persist = new PersistenceProperties();
        persist.setDb(dbPath.toString());
        SqliteDatabase sqlite = new SqliteDatabase(persist);
        sqlite.init();
        Jdbc jdbc = new Jdbc(sqlite);
        KbBlockStore blocks = new KbBlockStore(new KbBlockRepository(jdbc));
        blocks.init();
        KbBlockIndex corpus = new KbBlockIndex(blocks);
        corpus.reload();
        KbTermStore terms = new KbTermStore(new KbTermRepository(jdbc), corpus);
        terms.init();

        List<KbTermStore.Entry> todo = new ArrayList<>();
        List<String> titles = new ArrayList<>();
        List<String> excerpts = new ArrayList<>();
        for (KbTermStore.Entry e : terms.list()) {
            if (!e.en().startsWith("wiki-") || hasCjk(e.zh())) {
                continue;
            }
            KbBlock b = blocks.get(EntryId.of(e.en()));
            if (b == null || b.title() == null) {
                continue;
            }
            String title = b.title().replaceAll("（第 \\d+ 块）$", "").trim();
            if (title.isEmpty()) {
                continue;
            }
            todo.add(e);
            titles.add(title);
            String body = b.body() == null ? "" : b.body().replaceAll("\\s+", " ");
            excerpts.add(body.substring(0, Math.min(160, body.length())));
            if (todo.size() >= limit) {
                break;
            }
        }
        System.out.println();
        System.out.println("================ 大模型补别名 ================");
        System.out.printf("待办 %d 条（上限 %d），每批 %d 条%n", todo.size(), limit, BATCH);

        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build();
        ObjectMapper mapper = new ObjectMapper();
        int done = 0;
        int failed = 0;
        for (int i = 0; i < todo.size(); i += BATCH) {
            int end = Math.min(i + BATCH, todo.size());
            String prompt = buildPrompt(titles, excerpts, i, end);
            try {
                // 网络抖动实测有（Remote host terminated the handshake），每批重试
                String text = null;
                for (int attempt = 1; attempt <= 3 && text == null; attempt++) {
                    try {
                        text = chat(http, mapper, key, prompt);
                    } catch (Exception net) {
                        if (attempt == 3) {
                            throw net;
                        }
                        Thread.sleep(1500L * attempt);
                    }
                }
                int applied = apply(terms, todo, titles, i, end, text);
                done += applied;
                System.out.printf("  批 %d-%d：模型返回 %d 字，写入 %d 条%n", i + 1, end, text.length(), applied);
            } catch (Exception ex) {
                failed++;
                System.out.printf("  批 %d-%d 失败：%s%n", i + 1, end, ex.getMessage());
            }
        }
        System.out.printf("本次写入 %d 条，失败批次 %d%n", done, failed);
        sqlite.close();
    }

    /** 严格的输出格式：英文原名 ||| 别名1、别名2 —— 便于解析，也不让模型自由发挥 */
    private static String buildPrompt(List<String> titles, List<String> excerpts, int from, int to) {
        StringBuilder sb = new StringBuilder();
        sb.append("你在为《雾锁王国》（Enshrouded）的中文问答机器人维护术语别名表。资料来自英文 wiki。\n");
        sb.append("为下面每个英文条目给出 2~4 个中文别名，要求：\n");
        sb.append("1) 如果你知道游戏内的官方中文译名，优先给官方译名；\n");
        sb.append("2) 不确定就给最可能的字面直译（技能名按技能意译，生物名按外形或习性意译）；\n");
        sb.append("3) 再补 1~2 个玩家常说的口语叫法或简称；\n");
        sb.append("4) 只输出中文别名，用顿号分隔，不要解释、不要编号。\n");
        sb.append("输出格式严格如下，每行一条：\n");
        sb.append("<英文原名> ||| 别名1、别名2、别名3\n\n");
        for (int i = from; i < to; i++) {
            sb.append(titles.get(i)).append(" —— 摘录：").append(excerpts.get(i)).append("\n");
        }
        return sb.toString();
    }

    private static int apply(KbTermStore terms, List<KbTermStore.Entry> todo, List<String> titles,
                             int from, int to, String text) {
        int applied = 0;
        for (int i = from; i < to; i++) {
            String aliases = parseFor(text, titles.get(i));
            if (aliases == null || aliases.isBlank() || !hasCjk(aliases)) {
                continue;
            }
            // 写入走 upsert：英文名会被自动补上（en 是 slug，不能当关键词）
            if (terms.upsert(EntryId.of(todo.get(i).en()), aliases, "draft")) {
                applied++;
            }
        }
        return applied;
    }

    /** 从回复里取出某个标题那一行的别名 */
    private static String parseFor(String text, String title) {
        String key = title.toLowerCase();
        String quote = String.valueOf((char) 34) + (char) 39;
        for (String line : text.split("\\r?\\n")) {
            String l = line.trim();
            if (l.isEmpty()) {
                continue;
            }
            // 模型会飘：要求 |||，实测偶尔改成「标题：别名」「标题 —— 别名」「1. 标题 | 别名」。
            // 只认一种分隔符会整批白跑（实测批 65-72 就是），所以多认几种。
            for (String sep : new String[]{"|||", "：", ":", " | ", " —— ", " - "}) {
                int at = l.indexOf(sep);
                if (at <= 0) {
                    continue;
                }
                String head = l.substring(0, at).trim();
                String h = head.replaceAll("^[\\d.、)\\s]+", "").replace(quote, "").trim();
                if (h.equalsIgnoreCase(title) || h.toLowerCase().contains(key) || key.contains(h.toLowerCase())) {
                    return l.substring(at + sep.length()).trim().replaceAll("^[\\d.、)\\s]+", "");
                }
            }
        }
        return null;
    }

    private static String chat(HttpClient http, ObjectMapper mapper, String key, String prompt) throws Exception {
        ObjectNode body = mapper.createObjectNode();
        body.put("model", MODEL);
        ArrayNode messages = body.putArray("messages");
        ObjectNode user = messages.addObject();
        user.put("role", "user");
        user.put("content", prompt);
        body.put("temperature", 0.3);
        body.put("max_tokens", 2000);
        HttpRequest req = HttpRequest.newBuilder(URI.create(ENDPOINT))
                .timeout(Duration.ofSeconds(90))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + key)
                .header("User-Agent", "qqbot-personal/0.1")
                // opencode-go 要求带会话标识，否则 400 MissingSessionID（见 application.yml 的 headers）
                .header("x-opencode-session", "qqbot-personal")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                .build();
        HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() / 100 != 2) {
            throw new IllegalStateException("HTTP " + res.statusCode() + " "
                    + res.body().substring(0, Math.min(200, res.body().length())));
        }
        JsonNode root = mapper.readTree(res.body());
        return root.path("choices").path(0).path("message").path("content").asText("");
    }

    private static boolean hasCjk(String s) {
        return s != null && s.chars().anyMatch(c -> c >= 0x4e00 && c <= 0x9fff);
    }

    private static Path findRepoRoot() {
        Path dir = Path.of(System.getProperty("user.dir", ".")).toAbsolutePath().normalize();
        for (Path p = dir; p != null; p = p.getParent()) {
            if (Files.isRegularFile(p.resolve("server/data/qa/qqbot.sqlite"))) {
                return p;
            }
        }
        return Path.of(".").toAbsolutePath();
    }

    private static String readEnv(Path env, String name) throws Exception {
        if (!Files.isRegularFile(env)) {
            return null;
        }
        String q = String.valueOf((char) 34);
        for (String line : Files.readAllLines(env)) {
            String t = line.trim();
            if (t.startsWith(name + "=")) {
                String v = t.substring(name.length() + 1).trim();
                if (v.length() > 1 && v.startsWith(q) && v.endsWith(q)) {
                    v = v.substring(1, v.length() - 1);
                }
                return v;
            }
        }
        return null;
    }
}
