package com.example.qqbot.kb.term;

import com.example.qqbot.kb.EmbeddingClient;
import com.example.qqbot.kb.category.CategoryService;
import com.example.qqbot.kb.category.KbGroups;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.example.qqbot.kb.KnowledgeChangedEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 词条层 —— 把知识库整理成「分类 → 词条 → 文本块」三层来看。
 *
 * <h2>数据从哪来（都不是新造的）</h2>
 * <ul>
 *   <li><b>词条</b> = {@code chunks.jsonl} 的 {@code title}（Wiki 页面标题）
 *       <b>并上</b> {@link KbTermStore} 里那些没有正文的页面 ——
 *       {@code Equipment}、{@code Sets/*} 这类伞形词对 B 路检索有用
 *       （实测 Equipment 命中 44 块正文、Lore 命中 404 块），
 *       但它们没有正文，只从"有正文的块"出发根本看不见。</li>
 *   <li><b>中文名 / 核对状态</b> = {@link KbTermStore}，唯一真源。</li>
 *   <li><b>分类</b> = 每个块自带的 {@code cats[]} 经 {@link KbGroups#autoGroup} 映射到 9 个板块。</li>
 *   <li><b>文本块</b> = 块本身；<b>向量</b> = 块表里与块同 id 的那一条。</li>
 * </ul>
 *
 * <h2>这个类现在只负责「改名字」</h2>
 * 中文名 / 核对状态 → 委托 {@link KbTermStore}，单行 UPDATE，
 * 不碰语料、不需要 embedding、零成本。
 *
 * <p><b>改内容</b>（文本块的增删改下架）已经搬到
 * {@link com.example.qqbot.kb.block.KbBlockAdminService}。本类仍持有
 * {@link com.example.qqbot.kb.block.KbBlockStore} 与 {@link EmbeddingClient}，
 * 但只用于只读展示（这一页有没有正文、有没有向量）。
 *
 * <p>当年把两者分开的理由仍然成立：内容与向量必须成对更新 ——
 * 只改一边不会报错，只会让机器人**静默地带着错资料回答**。
 * 所以改内容走最严的路径，改名字根本不进那条路径。
 */
@Service
public class KbTermService {

    private static final Logger log = LoggerFactory.getLogger(KbTermService.class);

    /** 没有中文名，所以谈不上核对状态 */
    private static final String STATUS_NONE = "";

    /** 块表 —— 词条列表就是由它来的（**一个块 = 一个词条**） */
    private final com.example.qqbot.kb.block.KbBlockStore blockStore;
    private final KbTermStore termStore;
    private final CategoryService categoryService;
    private final EmbeddingClient embedding;
    private final ObjectMapper mapper;
    /**
     * 发布"知识库变了"的事件。
     *
     * <p>**不直接调公开站** —— 那是层次倒置（知识库不该知道公开站有检索缓存）。
     * 谁关心谁自己订阅，见 {@link KnowledgeChangedEvent}。
     */
    private final ApplicationEventPublisher events;

    public KbTermService(com.example.qqbot.kb.block.KbBlockStore blockStore, KbTermStore termStore,
                         CategoryService categoryService, EmbeddingClient embedding,
                         ObjectMapper mapper,
                         ApplicationEventPublisher events) {
        this.blockStore = blockStore;
        this.termStore = termStore;
        this.categoryService = categoryService;
        this.embedding = embedding;
        this.mapper = mapper;
        this.events = events;
    }

    /**
     * 每次成功写入后调一次 —— 宣布"知识库变了"。
     *
     * <p>以前这里直接调 {@code PublicSearchService.invalidate()}（还得用 ObjectProvider
     * 绕循环依赖）。现在只发事件，公开站自己订阅。
     */
    private void clearSearchCache() {
        events.publishEvent(new KnowledgeChangedEvent("词条变更"));
    }

    // ==================== 视图模型 ====================

    /**
     * 一条词条。
     *
     * @param en     英文名（= Wiki 页面标题 = 词条表的 key）
     * @param zh     中文名（可能为空）
     * @param aliases 中文别名（zh 用「、」等分隔）
     * @param status draft / verified / rejected / ""（"" = 还没有中文名）
     * @param board  所属板块 key；版本更新类页面归 {@code __hidden__}
     * @param chunkCount 这个词条被切成了几块（0 = 没有正文）
     * @param retired 该词条**全部**文本块都已下架
     */
    public record Term(String en, String zh, List<String> aliases, String status,
                       String board, String boardLabel, List<String> cats,
                       int chunkCount, int chars, String url, boolean retired) {
    }

    /**
     * 一个词条的文本块列表。
     *
     * <p>刻意包一层对象而不是直接返回数组：裸数组没法带上 {@code title}，
     * 前端在"抽屉打开的是哪一条"和"这批块属于哪一条"之间就没法对账 ——
     * 快速连点两条词条时，先到的响应会把后一条的块填进去。
     */
    public record ChunksView(String title, List<ChunkView> chunks) {
    }

    /**
     * 一个文本块。{@code i} 是它在 chunks.jsonl 里的行号，也是 index.bin 里的向量行号。
     *
     * @param retired 已下架。下架的块不参与任何检索，但仍留在文件里占位 ——
     *                
     */
    public record ChunkView(String id, String text, String url, int chars, boolean retired) {
    }

    /** 一个板块的规模 */
    public record BoardStat(String key, String label, String icon, String desc,
                            int terms, int chunks) {
    }

    /**
     * 全表读数。
     *
     * <p>刻意**不受搜索词/筛选影响** —— 它是"总共核了多少"的进度口径，
     * 不是"当前这一页里有几条"。旧术语表接口就是这个语义，别改。
     */
    public record Counts(int all, int main, int withChunks, int noChunk,
                         int draft, int verified, int rejected, int unnamed) {
    }

    /** 一页列表 */
    public record Page(int total, List<Term> items, Counts counts,
                       List<BoardStat> boards, boolean available) {
    }

    /** 视图预设。前端只传这一个参数，语义全在这里，省得每个筛选各写一遍 */
    public static final List<String> VIEWS =
            List.of("main", "all", "draft", "verified", "rejected", "unnamed", "nochunk");

    // ==================== 读 ====================

    /**
     * 词条列表。
     *
     * @param q     按英文名/中文名/别名模糊匹配；空 = 不过滤
     * @param view  见 {@link #VIEWS}；空 = main
     * @param board 只看某个板块；空 = 全部
     */
    public Page page(String q, String view, String board, int limit, int offset) {
        Map<String, Term> all = buildAll();
        Counts counts = count(all);
        List<BoardStat> boards = boardStats(all);
        boolean available = termStore.isAvailable();

        String needle = q == null ? "" : q.trim().toLowerCase(Locale.ROOT);
        String v = view == null || view.isBlank() ? "main" : view.trim().toLowerCase(Locale.ROOT);
        List<Term> filtered = new ArrayList<>();
        for (Term t : all.values()) {
            if (!matchesView(t, v)) {
                continue;
            }
            if (board != null && !board.isEmpty() && !board.equals(t.board())) {
                continue;
            }
            if (!needle.isEmpty() && !matchesQuery(t, needle)) {
                continue;
            }
            filtered.add(t);
        }
        // 按英文名排序：列表要能翻页，顺序必须稳定且可预期
        filtered.sort(Comparator.comparing(Term::en, String.CASE_INSENSITIVE_ORDER));

        int from = Math.max(0, offset);
        if (from >= filtered.size()) {
            return new Page(filtered.size(), List.of(), counts, boards, available);
        }
        int to = Math.min(filtered.size(), from + Math.max(1, limit));
        return new Page(filtered.size(), filtered.subList(from, to), counts, boards, available);
    }

    private static boolean matchesView(Term t, String view) {
        return switch (view) {
            case "all" -> true;
            // 默认视图：把「既没正文、又没中文名」的那 200 多个空页面挡在外面，
            // 它们只该在「无正文」预设里被主动看到
            case "main" -> t.chunkCount() > 0 || !t.zh().isEmpty();
            case "draft" -> "draft".equals(t.status());
            case "verified" -> "verified".equals(t.status());
            case "rejected" -> "rejected".equals(t.status());
            case "unnamed" -> t.zh().isEmpty();
            case "nochunk" -> t.chunkCount() == 0;
            default -> true;
        };
    }

    private static boolean matchesQuery(Term t, String needleLower) {
        if (t.en().toLowerCase(Locale.ROOT).contains(needleLower)) {
            return true;
        }
        if (t.zh().toLowerCase(Locale.ROOT).contains(needleLower)) {
            return true;
        }
        return t.aliases().stream().anyMatch(a -> a.toLowerCase(Locale.ROOT).contains(needleLower));
    }

    /** 某个词条（= 一份文档）下的所有文本块。含已下架的（管理端要看得到） */
    public List<ChunkView> chunks(String en) {
        List<ChunkView> out = new ArrayList<>();
        com.example.qqbot.kb.block.KbBlock b = blockStore.get(en);
        if (b != null) {
            String body = b.body() == null ? "" : b.body();
            out.add(new ChunkView(b.id(), body, b.url(), body.length(), b.retired()));
        }
        return out;
    }

    /**
     * 全表读数。批量改状态之后要回给前端刷新进度条 —— 增量更新数字容易算错，
     * 直接重算一遍最省心，代价是一次 4k 块的遍历。
     */
    public Counts counts() {
        return count(buildAll());
    }

    /** 词条是否存在（有没有正文都算） */
    public boolean exists(String en) {
        if (en == null || en.isBlank()) {
            return false;
        }
        if (termStore.get(en) != null) {
            return true;
        }
        return blockStore.get(en) != null;
    }

    // ==================== 组装 ====================

    /**
     * 把「有正文的页面」和「词条表里的页面」并成一份列表。
     *
     * <p>顺序是：先按 chunks 的 title 聚合（这样每个词条自带块数、分类、URL），
     * 再补上词条表里有、但没有任何块的页面。
     */
    private Map<String, Term> buildAll() {
        Map<String, KbTermStore.Entry> terms = termStore.map();
        Map<String, String> rawToGroup = categoryService.rawToGroup();
        Map<String, String> rawToLabel = categoryService.rawToLabel();

        Map<String, Term> out = new LinkedHashMap<>();
        // **一个块 = 一个词条**：词条身份就是块 id，中文名就是块标题。
        // 于是"词条"列表就是**分块的目录** —— 一份文档有多少块，就多出多少条词条。
        for (com.example.qqbot.kb.block.KbBlock b : blockStore.all()) {
            String key = b.id();
            String[] name = nameOf(terms.get(key));
            String zh = name[0].isEmpty() ? safe(b.title()) : name[0];
            String board = boardOf(b.tags(), rawToGroup);
            String body = b.body() == null ? "" : b.body();
            out.put(key, new Term(key, zh, splitAliases(zh), name[1],
                    board, boardLabel(board, rawToLabel), b.tags(),
                    1, body.length(), b.url(), b.retired()));
        }

        // 有名字或核对状态、但**没有正文**的页面（Equipment / Lore / Sets/* …）。
        // 它们对 B 路是真有用的：B 路是"中文换英文再去正文里找"，
        // 不要求那个英文词本身是某个块的标题。
        for (KbTermStore.Entry e : terms.values()) {
            if (out.containsKey(e.en())) {
                continue;
            }
            String[] name = nameOf(e);
            String board = KbGroups.DEFAULT_GROUP;
            out.put(e.en(), new Term(e.en(), name[0], splitAliases(name[0]), name[1],
                    board, boardLabel(board, rawToLabel), List.of(), 0, 0, "", false));
        }
        return out;
    }

    /**
     * 词条表的一行 → {@code [中文名, 状态]}。
     *
     * <p>没有中文名时状态一律报 {@code ""} 而不是 {@code draft}：在旧模型里
     * {@code ""} 的意思是"术语表里没有这条"，在新模型里对应的就是"还没起中文名"。
     * 不然那 500 多个空页面会全部冒充「待核对」，进度条立刻失真。
     */
    private static String[] nameOf(KbTermStore.Entry e) {
        if (e == null || e.zh().isBlank()) {
            return new String[]{"", STATUS_NONE};
        }
        return new String[]{safe(e.zh()), safe(e.status())};
    }

    private static Counts count(Map<String, Term> all) {
        int main = 0;
        int withChunks = 0;
        int noChunk = 0;
        int draft = 0;
        int verified = 0;
        int rejected = 0;
        int unnamed = 0;
        for (Term t : all.values()) {
            if (t.chunkCount() > 0) {
                withChunks++;
            } else {
                noChunk++;
            }
            if (t.zh().isEmpty()) {
                unnamed++;
            }
            if (t.chunkCount() > 0 || !t.zh().isEmpty()) {
                main++;
            }
            switch (t.status()) {
                case "verified" -> verified++;
                case "rejected" -> rejected++;
                case "draft" -> draft++;
                default -> {
                    // "" —— 还没中文名，不计入任何核对状态
                }
            }
        }
        return new Counts(all.size(), main, withChunks, noChunk, draft, verified, rejected, unnamed);
    }

    private static List<BoardStat> boardStats(Map<String, Term> all) {
        Map<String, int[]> perBoard = new LinkedHashMap<>();
        for (Term t : all.values()) {
            int[] acc = perBoard.computeIfAbsent(t.board(), x -> new int[2]);
            acc[0]++;
            acc[1] += t.chunkCount();
        }
        List<BoardStat> boards = new ArrayList<>();
        for (KbGroups.Group g : KbGroups.ALL) {
            int[] acc = perBoard.getOrDefault(g.key(), new int[2]);
            boards.add(new BoardStat(g.key(), g.label(), g.icon(), g.desc(), acc[0], acc[1]));
        }
        int[] hidden = perBoard.getOrDefault(KbGroups.HIDDEN, new int[2]);
        boards.add(new BoardStat(KbGroups.HIDDEN, "版本更新（不对外展示）", "",
                "更新日志、早期访问说明等，公开站隐藏", hidden[0], hidden[1]));
        return boards;
    }

    /**
     * 词条归属板块。
     *
     * <p>取<b>第一个非「版本更新类」的分类</b>。理由：2501 个块至少带一个隐藏分类，
     * 416 个块是「纯隐藏」；如果按 {@code cats[0]} 取，归属就由 Wiki 的编辑顺序决定，
     * 更新日志页会挤进玩家的分类里。没有任何可见分类时归 {@code __hidden__}；
     * 连分类都没有（308 个块）归「其他」。
     */
    private static String boardOf(List<String> cats, Map<String, String> rawToGroup) {
        if (cats == null || cats.isEmpty()) {
            return KbGroups.DEFAULT_GROUP;
        }
        String firstAny = null;
        for (String raw : cats) {
            String g = rawToGroup.getOrDefault(raw, KbGroups.autoGroup(raw));
            if (firstAny == null) {
                firstAny = g;
            }
            if (!KbGroups.HIDDEN.equals(g)) {
                return g;
            }
        }
        return firstAny == null ? KbGroups.DEFAULT_GROUP : firstAny;
    }

    private static String boardLabel(String board, Map<String, String> rawToLabel) {
        if (KbGroups.HIDDEN.equals(board)) {
            return "版本更新（不对外展示）";
        }
        KbGroups.Group g = KbGroups.get(board);
        return g == null ? board : g.label();
    }

    /** 中文名用「、」「/」「｜」「|」分隔多个别名（与 Glossary 的解析保持一致） */
    private static List<String> splitAliases(String zh) {
        if (zh.isEmpty()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String s : zh.split("[、/｜|]")) {
            String t = s.trim();
            if (!t.isEmpty()) {
                out.add(t);
            }
        }
        return out;
    }

    private static String safe(String s) {
        return s == null ? "" : s.trim();
    }

    // ==================== 写：名字层（委托词条表）====================

    /**
     * 改名字/状态。
     *
     * <p>不碰语料、不调 embedding、不重建索引 —— 这是「补术语表零成本」的技术依据。
     */
    public boolean saveName(String en, String zh, String status) {
        boolean ok = termStore.upsert(en, zh, status);
        if (ok) {
            clearSearchCache();
        }
        return ok;
    }

    /** 清掉中文名，状态回到 draft */
    public boolean clearName(String en) {
        boolean ok = termStore.clearName(en);
        if (ok) {
            clearSearchCache();
        }
        return ok;
    }

    /** 批量改核对状态；改完清一次公开站缓存 */
    public KbTermStore.BatchResult setStatusBatch(List<String> ens, String status) {
        KbTermStore.BatchResult r = termStore.setStatusBatch(ens, status);
        if (r.updated() > 0) {
            clearSearchCache();
        }
        return r;
    }

    // ==================== 写：查看/导出（给人看的格式）====================

    /** 5 列 TSV 的表头。导入时只认第 1/2/5 列，中间两列是给人看的 */
    private static final String TSV_HEADER = """
            # 知识库词条表 —— 中文名与核对状态
            # 列：英文名 <TAB> 中文名 <TAB> wiki页面 <TAB> 类别 <TAB> 状态
            # 状态：draft = 机器初稿未核对 ｜ verified = 已人工核对 ｜ rejected = 不要用
            # 中文名可以写多个别名，用「、」分隔
            # ⚠️ 导入时只读第 1、2、5 列；中间两列是导出时算出来的派生信息，改了没用
            """;

    /** 导出全表 TSV（Excel 旁路）。中间两列由语料现算，不落库 */
    public String exportTsv() {
        Map<String, Term> all = buildAll();
        List<Term> sorted = new ArrayList<>(all.values());
        sorted.sort(Comparator.comparing(Term::en, String.CASE_INSENSITIVE_ORDER));
        StringBuilder sb = new StringBuilder(TSV_HEADER);
        for (Term t : sorted) {
            if (t.zh().isEmpty() && t.chunkCount() == 0) {
                continue;   // 既没名字又没正文的空页面不进导出，免得淹掉有用的行
            }
            sb.append(t.en()).append('\t')
                    .append(t.zh()).append('\t')
                    .append(t.en().replace(' ', '_')).append('\t')
                    .append(t.cats().isEmpty() ? "" : t.cats().get(0)).append('\t')
                    .append(t.status().isEmpty() ? "draft" : t.status()).append('\n');
        }
        return sb.toString();
    }
}
