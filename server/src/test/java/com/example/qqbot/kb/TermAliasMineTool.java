package com.example.qqbot.kb;

import com.example.qqbot.config.PersistenceProperties;
import com.example.qqbot.kb.block.KbBlock;
import com.example.qqbot.kb.block.KbBlockIndex;
import com.example.qqbot.kb.block.KbBlockStore;
import com.example.qqbot.kb.term.KbTermStore;
import com.example.qqbot.kb.wiki.NameZhIndex;
import com.example.qqbot.persistence.Jdbc;
import com.example.qqbot.persistence.KbBlockRepository;
import com.example.qqbot.persistence.KbTermRepository;
import com.example.qqbot.persistence.SqliteDatabase;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 一次性运维工具：**给没有中文名的 wiki 词条补中文名**（确定性规则，不花钱）。
 *
 * <p>数据来源是语料自己：物品块标题大量是「中文、English」，其中包含实体名的派生条目
 * （{@code Fell Sicklescythe Trophy} / {@code Fell Sicklescythe Head}）。
 * {@link NameZhIndex} 从这些条目反推实体中文名，本工具把它写进词条表。
 *
 * <p>写入走 {@link KbTermStore#upsert}，所以英文名会被自动补上
 * （{@code en} 是 slug，不能当关键词 —— 见那边的注释）。
 *
 * <pre>
 * mvn test -Dtest=TermAliasMineTool -DfailIfNoTests=false -Dqqbot.tool.confirm=YES
 * </pre>
 */
class TermAliasMineTool {

    @Test
    @DisplayName("运维工具：从派生条目挖出实体中文名，写进词条表")
    void mine() throws Exception {
        Assumptions.assumeTrue("YES".equals(System.getProperty("qqbot.tool.confirm")),
                "需要 -Dqqbot.tool.confirm=YES 才动生产库");

        Path repo = findRepoRoot();
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

        NameZhIndex index = NameZhIndex.fromBlocks(blocks.all());
        // ---- 调试：索引里到底有没有派生条目 ----
        System.out.println("DEBUG 索引条数 " + index.size());
        int hit = 0;
        for (String probe : new String[]{"Fell Sicklescythe", "Fell Critter Queen", "Fell Cyclops", "Absorb"}) {
            System.out.println("DEBUG resolve(" + probe + ") = " + index.resolve(probe));
        }
        for (KbBlock b : blocks.all()) {
            if (b.title() != null && b.title().toLowerCase().contains("sicklescythe")) {
                System.out.println("DEBUG 标题含 sicklescythe：" + b.title() + "   [id=" + b.id() + "]");
                hit++;
                if (hit > 6) break;
            }
        }
        System.out.println();
        System.out.println("================ 中文名挖掘 ================");
        System.out.printf("语料 %d 块，从标题挖到中英对照 %d 条%n", blocks.count(), index.size());

        int wikiRows = 0;
        int already = 0;
        int mined = 0;
        int miss = 0;
        List<String> samples = new ArrayList<>();
        List<String> misses = new ArrayList<>();
        for (KbTermStore.Entry e : terms.list()) {
            if (!e.en().startsWith("wiki-")) {
                continue;
            }
            wikiRows++;
            if (hasCjk(e.zh())) {
                already++;
                continue;
            }
            String title = titleOf(blocks, e.en());
            if (title.isEmpty()) {
                miss++;
                continue;
            }
            String zh = index.resolve(title);
            if (zh == null || zh.isBlank() || !hasCjk(zh)) {
                miss++;
                if (misses.size() < 12) {
                    misses.add(title);
                }
                continue;
            }
            if (terms.upsert(EntryId.of(e.en()), zh, "draft")) {
                mined++;
                if (samples.size() < 15) {
                    samples.add(title + "  →  " + zh);
                }
            }
        }
        System.out.printf("wiki 词条 %d 条：原本有中文名 %d 条，本次补上 %d 条，仍查不到 %d 条%n",
                wikiRows, already, mined, miss);
        System.out.println();
        System.out.println("---- 补上的例子 ----");
        samples.forEach(s -> System.out.println("  " + s));
        System.out.println();
        System.out.println("---- 仍查不到的（要靠大模型补）----");
        misses.forEach(s -> System.out.println("  " + s));
        sqlite.close();
    }

    private static boolean hasCjk(String s) {
        return s != null && s.chars().anyMatch(c -> c >= 0x4e00 && c <= 0x9fff);
    }

    /** 词条的 en 是 slug，取对应块标题（切块的「（第 N 块）」后缀剥掉） */
    private static String titleOf(KbBlockStore blocks, String slug) {
        KbBlock b = blocks.get(EntryId.of(slug));
        if (b == null || b.title() == null) {
            return "";
        }
        return b.title().replaceAll("（第 \\d+ 块）$", "").trim();
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
}
