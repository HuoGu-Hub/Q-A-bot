package com.example.qqbot.kb;

import com.example.qqbot.config.KbProperties;
import com.example.qqbot.config.PersistenceProperties;
import com.example.qqbot.kb.block.KbBlock;
import com.example.qqbot.kb.block.KbBlockStore;
import com.example.qqbot.kb.wiki.KbWikiPageStore;
import com.example.qqbot.kb.wiki.KbWikiSourceStore;
import com.example.qqbot.kb.wiki.WikiApiClient;
import com.example.qqbot.kb.wiki.WikiArticleImporter;
import com.example.qqbot.persistence.Jdbc;
import com.example.qqbot.persistence.KbBlockRepository;
import com.example.qqbot.persistence.KbWikiPageRepository;
import com.example.qqbot.persistence.KbWikiSourceRepository;
import com.example.qqbot.persistence.SqliteDatabase;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 一次性运维工具：**清空语料 → 只导一个来源 → 导出核对数据**。
 *
 * <p>用途：清洗质量要人工核对时，先把语料清干净，只留一个分类，这样"库里有什么"
 * 与"wiki 上有什么"能一一对上，不会被其它来源的内容干扰。
 *
 * <h2>它会对**生产库**动手</h2>
 * 所以默认不跑：必须显式给 {@code -Dqqbot.tool.confirm=YES}。
 * 跑之前请先备份 {@code server/data/qa/qqbot.sqlite}（含 -wal / -shm）。
 *
 * <pre>
 * mvn test -Dtest=CorpusResetTool -DfailIfNoTests=false \
 *   -Dqqbot.tool.confirm=YES \
 *   -Dqqbot.tool.db=server/data/qa/qqbot.sqlite \
 *   -Dqqbot.tool.source=Bosses -Dqqbot.tool.kind=category \
 *   -Dqqbot.tool.mode=wipe-all \
 *   -Dqqbot.tool.dump=.toolchain/verify/bosses-dump.json
 * </pre>
 *
 * <p>{@code mode}：{@code wipe-all} 清掉**全部**块（含物品/文档/地点），
 * {@code purge-wiki} 只清 wiki 文章块。导入一律 {@code force}（忽略 revid 全部重洗）。
 */
class CorpusResetTool {

    @Test
    @DisplayName("运维工具：清空语料 + 单来源导入 + 导出核对数据")
    void run() throws IOException {
        Assumptions.assumeTrue("YES".equals(System.getProperty("qqbot.tool.confirm")),
                "需要 -Dqqbot.tool.confirm=YES 才动生产库");

        Path repo = findRepoRoot();
        Path dbPath = Path.of(System.getProperty("qqbot.tool.db",
                repo.resolve("server/data/qa/qqbot.sqlite").toString())).toAbsolutePath();
        String source = System.getProperty("qqbot.tool.source", "Bosses");
        String kind = System.getProperty("qqbot.tool.kind", "category");
        String mode = System.getProperty("qqbot.tool.mode", "wipe-all");
        Path dump = repo.resolve(System.getProperty("qqbot.tool.dump", ".toolchain/verify/corpus-dump.json"));

        System.out.println();
        System.out.println("================ 语料重置 ================");
        System.out.println("库：" + dbPath);
        System.out.println("范围：" + kind + " / " + source + "    清空模式：" + mode);

        ObjectMapper mapper = new ObjectMapper();
        PersistenceProperties persist = new PersistenceProperties();
        persist.setDb(dbPath.toString());
        SqliteDatabase sqlite = new SqliteDatabase(persist);
        sqlite.init();
        Jdbc jdbc = new Jdbc(sqlite);

        KbBlockStore blocks = new KbBlockStore(new KbBlockRepository(jdbc));
        blocks.init();
        KbWikiPageStore pages = new KbWikiPageStore(new KbWikiPageRepository(jdbc));
        pages.init();
        KbWikiSourceStore sources = new KbWikiSourceStore(new KbWikiSourceRepository(jdbc));
        sources.init();

        KbProperties kb = new KbProperties();
        String key = readEnv(repo.resolve(".env"), "SILICONFLOW_API_KEY");
        if (key != null && !key.isEmpty()) {
            kb.getEmbedding().setApiKey(key);
        }
        EmbeddingClient embedding = new EmbeddingClient(kb, mapper);
        WikiApiClient client = new WikiApiClient(kb, mapper);
        WikiArticleImporter importer = new WikiArticleImporter(kb, client, pages, blocks, embedding, sources);

        // ① 范围只留这一个来源
        sources.replace(List.of(new KbWikiSourceRepository.Source(source, kind, true, "")));
        System.out.println("范围已设为：" + source + "（" + kind + "）");

        // ② 清空
        int before = blocks.count();
        if ("purge-wiki".equals(mode)) {
            int n = importer.purge();
            System.out.println("purge-wiki：删掉 wiki 块 " + n + " 个");
        } else {
            blocks.clearAll();
            pages.clear();
            System.out.println("wipe-all：清空前 " + before + " 块 → 现在 " + blocks.count() + " 块");
        }

        // ③ 导入（force）
        WikiArticleImporter.ImportReport r = importer.importAll(false, true, (phase, done, total) ->
                System.out.println("    " + phase + " " + done + "/" + total));
        System.out.println();
        System.out.println("导入报告：来源 " + r.sources() + " 个 / 页面 " + r.pages() + " 篇 / 变过 " + r.changed()
                + " 篇 / 写入 " + r.imported() + " 块 / " + r.chars() + " 字 / 渲染兜底 " + r.rendered()
                + " 页 / 失败 " + r.failed());
        r.errors().forEach(e -> System.out.println("   ! " + e));

        // ④ 导出核对数据
        List<Map<String, Object>> blockRows = new ArrayList<>();
        for (KbBlock b : blocks.all()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", b.id().toString());
            m.put("docId", b.docId());
            m.put("title", b.title());
            m.put("url", b.url());
            m.put("tags", b.tags());
            m.put("source", b.source());
            m.put("chars", b.body() == null ? 0 : b.body().length());
            m.put("body", b.body());
            blockRows.add(m);
        }
        List<Map<String, Object>> pageRows = new ArrayList<>();
        for (KbWikiPageRepository.Page p : pages.pages()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("page", p.page());
            m.put("source", p.source());
            m.put("revid", p.revid());
            m.put("chars", p.chars());
            m.put("blockId", p.blockId());
            m.put("blockCount", p.blockCount());
            m.put("error", p.error());
            pageRows.add(m);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("source", source);
        out.put("kind", kind);
        out.put("mode", mode);
        out.put("report", Map.of("pages", r.pages(), "imported", r.imported(), "chars", r.chars(),
                "rendered", r.rendered(), "failed", r.failed(), "bySource", r.bySource()));
        out.put("pages", pageRows);
        out.put("blocks", blockRows);
        Files.createDirectories(dump.getParent());
        Files.writeString(dump, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(out));
        System.out.println("核对数据已写出：" + dump);

        System.out.println();
        System.out.println("---- 逐页 ----");
        for (KbWikiPageRepository.Page p : pages.pages()) {
            System.out.printf("  %-42s %5d 字  %d 块%n", p.page(), p.chars(), p.blockCount());
        }
        sqlite.close();
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

    private static String readEnv(Path env, String name) throws IOException {
        if (!Files.isRegularFile(env)) {
            return null;
        }
        for (String line : Files.readAllLines(env)) {
            String t = line.trim();
            if (t.startsWith(name + "=")) {
                String v = t.substring(name.length() + 1).trim();
                if (v.length() > 1 && v.startsWith("\"") && v.endsWith("\"")) {
                    v = v.substring(1, v.length() - 1);
                }
                return v;
            }
        }
        return null;
    }
}
