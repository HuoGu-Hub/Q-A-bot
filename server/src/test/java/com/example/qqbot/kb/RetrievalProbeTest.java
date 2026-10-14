package com.example.qqbot.kb;

import com.example.qqbot.config.KbProperties;
import com.example.qqbot.config.PersistenceProperties;
import com.example.qqbot.config.QaProperties;
import com.example.qqbot.kb.block.KbBlockIndex;
import com.example.qqbot.kb.block.KbBlockStore;
import com.example.qqbot.persistence.Jdbc;
import com.example.qqbot.persistence.KbBlockRepository;
import com.example.qqbot.persistence.QaStoreRepository;
import com.example.qqbot.persistence.SqliteDatabase;
import com.example.qqbot.qa.QaStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * 检索探针 —— 把任意查询丢进**真实检索栈**，打印它到底命中了什么。
 *
 * <p>为什么需要它：用户报「中文搜 Fell Sicklescythe 命中不了」这类问题时，
 * 光看代码推不出来 —— 必须看真实的候选、分数、来源。它复用
 * {@link RetrievalGoldenTest} 那套搭栈方式（生产库副本 + 真实 KbRetriever），
 * 不重新实现算法。
 *
 * <p>用法（查询放文件里，避免中文在命令行里被转码）：
 * <pre>
 * mvn test -Dtest=RetrievalProbeTest -DfailIfNoTests=false \
 *   -Dqqbot.probe.file=.toolchain/probe-queries.txt -Dqqbot.probe.expect=wiki-fell-sicklescythe
 * </pre>
 */
class RetrievalProbeTest {

    @TempDir
    Path tmp;

    @Test
    @DisplayName("探针：打印每条查询的真实命中与分数")
    void probe() throws IOException {
        Path repo = findRepoRoot();
        if (repo == null) {
            System.out.println("找不到工程根，跳过");
            return;
        }
        Path prodDb = repo.resolve("server/data/qa/qqbot.sqlite");
        Path db = tmp.resolve("corpus.sqlite");
        Files.copy(prodDb, db);
        for (String side : new String[]{"-wal", "-shm"}) {
            Path s = Path.of(prodDb + side);
            if (Files.exists(s)) {
                Files.copy(s, Path.of(db + side));
            }
        }

        ObjectMapper mapper = new ObjectMapper();
        PersistenceProperties persist = new PersistenceProperties();
        persist.setDb(db.toString());
        SqliteDatabase sqlite = new SqliteDatabase(persist);
        sqlite.init();
        QaProperties qa = new QaProperties();
        QaStore qaStore = new QaStore(new QaStoreRepository(new Jdbc(sqlite)), qa, mapper);
        qaStore.init();
        KbBlockStore blocks = new KbBlockStore(new KbBlockRepository(new Jdbc(sqlite)));
        blocks.init();
        KbBlockIndex corpus = new KbBlockIndex(blocks);
        corpus.reload();

        KbProperties kb = new KbProperties();
        String key = readEnv(repo.resolve(".env"), "SILICONFLOW_API_KEY");
        if (key != null && !key.isEmpty()) {
            kb.getEmbedding().setApiKey(key);
            kb.getRerank().setApiKey(key);
        }
        EmbeddingClient embedding = new EmbeddingClient(kb, mapper);
        RerankClient rerank = new RerankClient(kb, mapper);
        // 词条就是分块：名字源直接是块存储
        Glossary glossary = new Glossary(blocks);
        KbRetriever retriever = new KbRetriever(kb, corpus, glossary, embedding, rerank);

        String expect = System.getProperty("qqbot.probe.expect", "");
        Path qf = Path.of(System.getProperty("qqbot.probe.file",
                repo.resolve(".toolchain/probe-queries.txt").toString()));
        List<String> queries = Files.readAllLines(qf).stream()
                .map(String::trim).filter(s -> !s.isEmpty() && !s.startsWith("#")).toList();

        System.out.println();
        System.out.println("================ 检索探针 ================");
        System.out.printf("语料 %d 块 / 术语表 %d 条 / 向量路 %s / 重排 %s%n",
                blocks.count(), glossary.size(), embedding.isAvailable() ? "可用" : "不可用",
                rerank.isAvailable() ? "可用" : "关闭");
        for (String q : queries) {
            KbRetriever.Retrieval ret = retriever.retrieve(q);
            System.out.println();
            System.out.printf("查询【%s】 向量候选 %d / 关键词候选 %d / bestCosine %.4f（raw %.4f）%n",
                    q, ret.vectorCandidates(), ret.keywordCandidates(),
                    ret.bestCosine(), ret.bestCosineRaw());
            System.out.printf("  命中术语：%s%n", ret.matchedTerms().isEmpty() ? "（无）"
                    : ret.matchedTerms().stream().map(String::valueOf).toList());
            int rank = 0;
            for (KbRetriever.Hit h : ret.hits()) {
                rank++;
                if (rank > 20) {
                    break;
                }
                String text = h.entry().text() == null ? "" : h.entry().text().replaceAll("\\s+", " ");
                String mark = h.entry().id().toString().equals(expect) ? "  ★★★ 就是它" : "";
                System.out.printf("  %2d. %-42s [%s] %.4f  %s%s%n", rank, h.entry().id().toString(),
                        h.source(), h.score(), text.substring(0, Math.min(90, text.length())), mark);
            }
            int at = 0;
            int i = 0;
            for (KbRetriever.Hit h : ret.hits()) {
                i++;
                if (h.entry().id().toString().equals(expect)) {
                    at = i;
                    break;
                }
            }
            System.out.printf("  → 期望块 %s：%s%n", expect,
                    at == 0 ? "★ 完全没进候选" : "第 " + at + " 名");
        }

        // ---- 深挖：期望块的真实余弦、全库排名、重排分 ----
        KbCorpus.Entry target = corpus.entries().stream()
                .filter(e -> e.id().toString().equals(expect)).findFirst().orElse(null);
        System.out.println();
        System.out.println("================ 深挖 " + expect + " ================");
        if (target == null) {
            System.out.println("语料里没有这个块");
            sqlite.close();
            return;
        }
        System.out.printf("标题：%s%n正文：%s%n", target.title(),
                target.text().replaceAll("\\s+", " ").substring(0, Math.min(200, target.text().length())));
        float[] targetBody = corpus.vector(target.id());
        float[] targetTitle = corpus.titleVector(target.id());
        for (String q : queries) {
            float[] qv = embedding.embedOne(q);
            double cBody = targetBody == null ? -1 : cosine(qv, targetBody);
            double cTitle = targetTitle == null ? -1 : cosine(qv, targetTitle);
            int rankBody = 0;
            int rankTitle = 0;
            for (KbCorpus.Entry e : corpus.entries()) {
                float[] bv = corpus.vector(e.id());
                if (bv != null && cosine(qv, bv) > cBody) {
                    rankBody++;
                }
                float[] tv2 = corpus.titleVector(e.id());
                if (tv2 != null && cosine(qv, tv2) > cTitle) {
                    rankTitle++;
                }
            }
            System.out.printf("【%s】正文余弦 %.4f（全库第 %d 名）/ 标题余弦 %.4f（第 %d 名）%n",
                    q, cBody, rankBody + 1, cTitle, rankTitle + 1);
        }
        // 重排直接打分：把正确块和当前第一名放一起比
        KbRetriever.Retrieval top = retriever.retrieve(queries.get(0));
        if (!top.hits().isEmpty() && rerank.isAvailable()) {
            String rival = top.hits().get(0).entry().text();
            double[] s = rerank.score(queries.get(0), List.of(target.text(), rival));
            System.out.printf("重排直评【%s】正确块 %.4f vs 当前第一名(%s) %.4f%n",
                    queries.get(0), s[0], top.hits().get(0).entry().id(), s[1]);
        }
        System.out.println();
        System.out.println("---- 词条表里与 Sickle / 镰刀 / 堕落 相关的行 ----");
        for (String[] row : new String[][]{{"镰刀", "Sickle"}, {"堕落", "Fell"}, {"scythe", "Scythe"}}) {
            int n = 0;
            for (var r : new Jdbc(sqlite).query(
                    "SELECT en, zh, status FROM kb_term WHERE zh LIKE ? OR en LIKE ?",
                    rr -> new String[]{rr.str("en"), rr.str("zh"), rr.str("status")},
                    "%" + row[0] + "%", "%" + row[1] + "%")) {
                System.out.println("  " + r[0] + " ↔ " + r[1] + "  [" + r[2] + "]");
                n++;
            }
            if (n == 0) {
                System.out.println("  （词条表里没有 " + row[0] + " / " + row[1] + " 的任何映射）");
            }
        }
        sqlite.close();
    }

    private static double cosine(float[] a, float[] b) {
        double dot = 0;
        double na = 0;
        double nb = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            na += a[i] * a[i];
            nb += b[i] * b[i];
        }
        return dot / (Math.sqrt(na) * Math.sqrt(nb) + 1e-9);
    }

    private static Path findRepoRoot() {
        Path dir = Path.of(System.getProperty("user.dir", ".")).toAbsolutePath().normalize();
        for (Path p = dir; p != null; p = p.getParent()) {
            if (Files.isRegularFile(p.resolve("server/data/qa/qqbot.sqlite"))) {
                return p;
            }
        }
        return null;
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
