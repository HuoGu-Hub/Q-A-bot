package com.example.qqbot.kb;

import com.example.qqbot.config.KbProperties;
import com.example.qqbot.config.QaProperties;
import com.example.qqbot.kb.block.KbBlockIndex;
import com.example.qqbot.kb.block.KbBlockStore;
import com.example.qqbot.kb.term.KbTermStore;
import com.example.qqbot.qa.QaStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 检索黄金集跑手 —— **回答质量的回归基线**。
 *
 * <h2>它解决什么问题</h2>
 * 现状 {@link KbRetrieverTest} 测的是"三路各自有没有工作"，不测"口语提问答得对不对"。
 * 没有度量，后面所有"口语问答变好了"的说法都只能靠感觉。
 *
 * <h2>怎么保证测的是真东西</h2>
 * <ul>
 *   <li>用**生产语料**（复制一份 {@code server/data/qa/qqbot.sqlite} 再读，不碰真库）；</li>
 *   <li>用**真实生产参数**（{@code top-k=5}、{@code min-score=0.45}、{@code title-gate=0.70}）；</li>
 *   <li>走**真实检索器** {@link KbRetriever}，不重新实现算法（否则就是两套必然漂移的算法）。</li>
 * </ul>
 *
 * <h2>四类分开统计</h2>
 * 混在一起算平均值会掩盖问题：现状 {@code entity} 类接近满分、{@code gap} 类全错，
 * 平均下来"看着还行"，但群友体感就是"一问位置就胡说"。
 *
 * <p>没有生产库时**跳过**（{@link Assumptions#assumeTrue}）—— 它是度量工具，
 * 不该让缺数据的机器构建失败。
 */
class RetrievalGoldenTest {

    @TempDir
    Path tmp;

    private QaStore qaStore;

    /** 一条黄金样本 */
    private record Item(String category, String question, Set<String> expected, String note) {
        boolean expectsAnswer() {
            return !expected.isEmpty();
        }
    }

    @AfterEach
    void tearDown() {
        if (qaStore != null) {
            qaStore.close();
        }
    }

    @Test
    @DisplayName("★ 检索黄金集基线：分类统计 R@1 / R@5 / MRR / 干净率")
    void goldenBaseline() throws IOException {
        Path repo = findRepoRoot();
        Assumptions.assumeTrue(repo != null, "找不到工程根（缺 tests/golden/retrieval-golden.tsv），跳过");
        Path prodDb = repo.resolve("server/data/qa/qqbot.sqlite");
        Path golden = repo.resolve("tests/golden/retrieval-golden.tsv");
        Assumptions.assumeTrue(Files.isRegularFile(prodDb), "没有生产语料库，跳过：" + prodDb);
        Assumptions.assumeTrue(Files.isRegularFile(golden), "没有黄金集，跳过：" + golden);

        // ---- 复制语料（绝不直接读生产库：WAL 共享内存 + 迁移会写库）----
        Path db = tmp.resolve("corpus.sqlite");
        Files.copy(prodDb, db);
        for (String side : new String[]{"-wal", "-shm"}) {
            Path s = Path.of(prodDb + side);
            if (Files.exists(s)) {
                Files.copy(s, Path.of(db + side));
            }
        }

        ObjectMapper mapper = new ObjectMapper();
        QaProperties qa = new QaProperties();
        qa.setDb(db.toString());
        qaStore = new QaStore(qa, mapper);
        qaStore.init();

        KbBlockStore blocks = new KbBlockStore(qaStore);
        blocks.init();
        KbBlockIndex corpus = new KbBlockIndex(blocks);
        corpus.reload();

        KbProperties kb = new KbProperties();
        // 有 key 就开向量路（真实生产路径）；没有就退化成本地关键词路，仍然可跑
        String key = readEnv(repo.resolve(".env"), "SILICONFLOW_API_KEY");
        if (key != null && !key.isEmpty()) {
            kb.getEmbedding().setApiKey(key);
        }
        EmbeddingClient embedding = new EmbeddingClient(kb, mapper);
        KbTermStore terms = new KbTermStore(qaStore, kb, mapper, corpus);
        terms.init();
        Glossary glossary = new Glossary(terms);
        KbRetriever retriever = new KbRetriever(kb, corpus, glossary, embedding);

        // 向量路到底活着没有 —— 决定下面要不要设门槛。
        // 外面没网 / key 失效时 EmbeddingClient 会退化，A 路与 C 路一起静默跳过，
        // 此时测出来的是"只有本地关键词路"的另一种分布，拿它去卡门槛会变成假红。
        boolean vectorLive = embedding.isAvailable()
                && retriever.retrieve("向量路可用性自检").bestCosineRaw() > 0;

        System.out.println();
        System.out.println("================ 检索黄金集基线 ================");
        System.out.printf("语料：%d 块（维度 %d），标题向量 %d 条%n",
                blocks.count(), blocks.dimensions(), blocks.countTitleVectors());
        System.out.printf("术语表：%d 条%n", glossary.size());
        System.out.printf("向量路：%s（top-k=%d, min-score=%.2f, title-gate=%.2f）%n",
                vectorLive ? "已启用且可用（会产生 API 调用）"
                        : embedding.isAvailable() ? "★ 不可用（调用失败）—— 本次只跑本地关键词路，结果不可与基线比较"
                        : "未启用（无 SILICONFLOW_API_KEY，只跑本地关键词路）",
                kb.getTopK(), kb.getMinScore(), kb.getTitleGate());

        List<Item> items = load(golden);
        Map<String, List<Item>> byCat = new LinkedHashMap<>();
        for (Item it : items) {
            byCat.computeIfAbsent(it.category(), k -> new ArrayList<>()).add(it);
        }

        System.out.println();
        System.out.printf("%-12s %5s %8s %8s %8s %10s %12s%n",
                "类别", "条数", "R@1", "R@5", "MRR", "干净率", "注入块均值");
        Map<String, Stats> stats = new LinkedHashMap<>();
        for (Map.Entry<String, List<Item>> e : byCat.entrySet()) {
            // descriptive 尚未人工标注 —— 不参与打分，只在下面打印 Top-5 供判定
            if (e.getKey().equals("descriptive")) {
                System.out.printf("%-12s %5d %8s %8s %8s %10s %12s%n",
                        e.getKey(), e.getValue().size(), "-", "-", "-", "-", "-");
                continue;
            }
            stats.put(e.getKey(), report(e.getKey(), e.getValue(), retriever));
        }

        System.out.println();
        System.out.println("---- descriptive（尚未人工标注，打印 Top-5 供判定）----");
        for (Item it : byCat.getOrDefault("descriptive", List.of())) {
            List<KbRetriever.Hit> hits = retriever.retrieve(it.question()).hits();
            System.out.printf("  %s%n      -> %s%n", it.question(),
                    hits.isEmpty() ? "（空）" : preview(hits));
        }
        System.out.println("================ 基线结束 ================");

        // ---------------- 回归门槛 ----------------
        // 只有『本来就该做对』的类别设门槛：它们的下降一定是回归。
        // gap / chitchat 现在本来就是 0% 干净率，那是 Phase 2 要修的**目标**而不是门槛 ——
        // 把它们写成断言只会让构建常年发红，反而没人看。目标记在这里：
        //   gap      : 5 条，干净率 0%（全部注入了 5 块）
        //   chitchat : 15 条，干净率 6.7%（15 条里只有 1 条没注入）
        //   → Phase 2 的 D1（意图分流）+ D5（相对阈值）完成后，这两个数字必须显著上升。
        if (!vectorLive) {
            System.out.println("★ 向量路不可用，本次不设回归门槛（只打印结果）");
        }
        Stats ent = vectorLive ? stats.get("entity") : null;
        if (ent != null && ent.n() > 0) {
            assertTrue(ent.r1() >= Math.ceil(ent.n() * 0.58),
                    "entity R@1 低于门槛（基线 66.7%）：" + ent.r1() + "/" + ent.n());
            assertTrue(ent.r5() >= Math.ceil(ent.n() * 0.83),
                    "entity R@5 低于门槛（基线 100%）：" + ent.r5() + "/" + ent.n());
        }
        Stats col = vectorLive ? stats.get("colloquial") : null;
        if (col != null && col.n() > 0) {
            assertTrue(col.r5() >= Math.ceil(col.n() * 0.75),
                    "colloquial R@5 低于门槛（基线 100%）：" + col.r5() + "/" + col.n());
        }
        int junkBlocks = (stats.containsKey("gap") ? stats.get("gap").injected() : 0)
                + (stats.containsKey("chitchat") ? stats.get("chitchat").injected() : 0);
        System.out.printf("Phase 2 待修：gap+chitchat 共注入 %d 块无关资料（今天就是这个问题）%n", junkBlocks);
        System.out.println();
    }

    /** 一个类别的统计结果 */
    private record Stats(int n, int r1, int r5, int clean, int injected, double mrr) {
    }

    /** 分类统计并打印，同时列出逐条明细 */
    private Stats report(String category, List<Item> items, KbRetriever retriever) {
        int n = 0, r1 = 0, r5 = 0, clean = 0;
        double mrr = 0;
        int injected = 0;
        List<String> details = new ArrayList<>();

        for (Item it : items) {
            List<KbRetriever.Hit> hits = retriever.retrieve(it.question()).hits();
            n++;
            injected += hits.size();
            if (it.expectsAnswer()) {
                int rank = rankOf(hits, it.expected());
                if (rank == 1) r1++;
                if (rank >= 1 && rank <= 5) r5++;
                if (rank >= 1) mrr += 1.0 / rank;
                details.add(String.format("    %s  rank=%s  %s%n        -> %s",
                        rank == 1 ? "R@1 " : rank > 0 ? "R@" + rank : "MISS",
                        rank == 0 ? "-" : String.valueOf(rank), it.question(),
                        hits.isEmpty() ? "（空）" : preview(hits)));
            } else {
                boolean isClean = hits.isEmpty();
                if (isClean) clean++;
                if (!isClean) {
                    details.add(String.format("    ✗ 不该注入却注入了 %d 块：%s%n        -> %s",
                            hits.size(), it.question(), preview(hits)));
                }
            }
        }

        System.out.printf("%-12s %5d %8s %8s %8s %10s %12.1f%n", category, n,
                pct(r1, n), pct(r5, n), n == 0 ? "-" : String.format("%.3f", mrr / n),
                pct(clean, n), (double) injected / Math.max(1, n));

        if (category.equals("entity") || category.equals("colloquial")) {
            System.out.println("  ---- " + category + " 逐条 ----");
            details.forEach(System.out::println);
        } else if (!details.isEmpty()) {
            System.out.println("  ---- " + category + " 不该注入的 ----");
            details.forEach(System.out::println);
        }
        return new Stats(n, r1, r5, clean, injected, mrr / Math.max(1, n));
    }

    /** 第一条命中期望块的排名（1 起）；没命中返回 0 */
    private static int rankOf(List<KbRetriever.Hit> hits, Set<String> expected) {
        for (int i = 0; i < hits.size(); i++) {
            if (expected.contains(hits.get(i).entry().id())) {
                return i + 1;
            }
        }
        return 0;
    }

    private static String preview(List<KbRetriever.Hit> hits) {
        StringBuilder sb = new StringBuilder();
        int i = 1;
        for (KbRetriever.Hit h : hits) {
            if (i > 1) {
                sb.append(" | ");
            }
            sb.append(h.entry().id()).append('(').append(i++).append(')');
        }
        return sb.toString();
    }

    private static String pct(int num, int den) {
        return den == 0 ? "-" : String.format("%.1f%%", 100.0 * num / den);
    }

    private static List<Item> load(Path file) throws IOException {
        List<Item> out = new ArrayList<>();
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            if (line.isBlank() || line.startsWith("#")) {
                continue;
            }
            String[] f = line.split("\t", -1);
            if (f.length < 3) {
                continue;
            }
            Set<String> exp = new LinkedHashSet<>();
            // '?' 表示尚未人工标注：不参与打分，但要在报告里列出来等人处理
            if (!f[2].isBlank() && !f[2].equals("-") && !f[2].equals("?")) {
                for (String id : f[2].split(";")) {
                    if (!id.isBlank()) {
                        exp.add(id.trim());
                    }
                }
            }
            if (f[2].equals("?")) {
                out.add(new Item("descriptive", f[1], Set.of(), f.length > 3 ? f[3] : ""));
            } else {
                out.add(new Item(f[0], f[1], exp, f.length > 3 ? f[3] : ""));
            }
        }
        return out;
    }

    /**
     * 找工程根。
     *
     * <p><b>为什么不用 {@code ProjectFiles.projectRoot()}</b>：它以
     * {@code AGENTS.md} / {@code server/pom.xml} 为判据向上找，而隔离构建
     * （{@code scripts/build-server.sh}）会把 {@code AGENTS.md} 复制到
     * {@code .toolchain/AGENTS.md} —— 于是从 {@code .toolchain/server-ci} 向上找时，
     * 先撞上 {@code .toolchain}，把工程根认成了 {@code .toolchain}，
     * 生产语料与黄金集就都找不到了（表现为整个测试被静默跳过）。
     *
     * <p>所以这里用**只在仓库根存在**的判据：黄金集文件本身，或生产语料库。
     */
    private static Path findRepoRoot() {
        String override = System.getProperty("qqbot.repo.root");
        if (override != null && !override.isBlank()) {
            Path p = Path.of(override).toAbsolutePath().normalize();
            return Files.isDirectory(p) ? p : null;
        }
        Path dir = Path.of(System.getProperty("user.dir", ".")).toAbsolutePath().normalize();
        for (Path p = dir; p != null; p = p.getParent()) {
            if (Files.isRegularFile(p.resolve("tests/golden/retrieval-golden.tsv"))
                    || Files.isRegularFile(p.resolve("server/data/qa/qqbot.sqlite"))) {
                return p;
            }
        }
        return null;
    }

    /** 只读一个 key，**不打印值** */
    private static String readEnv(Path envFile, String key) {
        if (!Files.isRegularFile(envFile)) {
            return null;
        }
        try {
            for (String line : Files.readAllLines(envFile, StandardCharsets.UTF_8)) {
                String t = line.trim();
                if (t.startsWith(key + "=")) {
                    return t.substring(key.length() + 1).trim();
                }
            }
        } catch (IOException ignored) {
            // 读不到就当没有 key
        }
        return null;
    }
}
