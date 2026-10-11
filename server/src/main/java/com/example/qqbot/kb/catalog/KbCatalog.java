package com.example.qqbot.kb.catalog;

import com.example.qqbot.kb.block.KbBlock;
import com.example.qqbot.kb.block.KbBlockStore;
import com.example.qqbot.kb.category.CategoryService;
import com.example.qqbot.kb.category.KbGroups;
import com.example.qqbot.kb.block.KbDocImporter;
import com.example.qqbot.kb.term.KbTermService;
import com.example.qqbot.kb.term.KbTermStore;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 知识库组合层：把「块」与「名字」两半合成一个目录读模型。
 *
 * <h2>为什么需要它</h2>
 * 块与名字各自是真源 —— 内容与向量必须成对更新，改名字零成本，
 * 两种变化原因不同，所以它们在两个包里。但这两半共享同一个身份：
 * <b>块 id 就是词条表的 en</b>。于是"合成"这件事没有主人：
 * 它原先塞在 {@code KbTermService} 里，靠一只伸进块包的手完成。
 *
 * <p>本类就是那个主人。它同时依赖 {@link KbBlockStore} 与 {@link KbTermStore}，
 * 是<b>唯一</b>同时拥有两半的地方。依赖方向因此只剩自上而下一条：
 * 两个真源互不认识，谁也不认识组合层的内部。
 *
 * <h2>它负责什么</h2>
 * <ul>
 *   <li>目录读模型：{@code 分类 → 词条 → 文本块} 三层视图，供列表与导出共用；</li>
 *   <li>按视图筛选与统计口径见 {@link KbCatalogViews}，导出格式见 {@link KbCatalogExport}；</li>
 *   <li>孤儿词条清理：拿"现在有哪些块"与词条表比对。</li>
 * </ul>
 *
 * <p><b>写名字不在这里</b> —— 那是名字层的事，见 {@code kb.term.KbTermService}。
 * 本类只读两个真源，唯二的写动作是"删孤儿词条"这类跨两半的清理。
 */
@Component
public class KbCatalog {

    /** 没有中文名，所以谈不上核对状态 */
    private static final String STATUS_NONE = "";

    /** 块表 —— 目录里"有正文的页面"由它来 */
    private final KbBlockStore blockStore;
    /** 名字层 —— 中文名、核对状态、以及没有正文的页面 */
    private final KbTermStore termStore;
    private final CategoryService categoryService;
    /** 块侧的文档导入器 —— 只落块、只提名字建议 */
    private final KbDocImporter importer;
    /** 名字层的写入口 —— 决定哪些建议真正落库 */
    private final KbTermService termService;

    public KbCatalog(KbBlockStore blockStore, KbTermStore termStore, CategoryService categoryService,
                     KbDocImporter importer, KbTermService termService) {
        this.blockStore = blockStore;
        this.termStore = termStore;
        this.categoryService = categoryService;
        this.importer = importer;
        this.termService = termService;
    }

    // ==================== 视图模型 ====================

    /**
     * 一条词条。
     *
     * @param en         英文名（= Wiki 页面标题 = 词条表的 key）
     * @param zh         中文名（可能为空）
     * @param aliases    中文别名（zh 用「、」等分隔）
     * @param status     draft / verified / rejected / ""（"" = 还没有中文名）
     * @param board      所属板块 key；版本更新类页面归 {@code __hidden__}
     * @param chunkCount 这个词条被切成了几块（0 = 没有正文）
     * @param retired    该词条**全部**文本块都已下架
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

    /** 一个文本块。{@code id} 是它在块表里的身份，也是检索时的单位 */
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

    /** 视图预设 —— 定义在 {@link KbCatalogViews}，这里只是留给调用方的公开别名 */
    public static final List<String> VIEWS = KbCatalogViews.VIEWS;

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
        Counts counts = KbCatalogViews.count(all);
        List<BoardStat> boards = KbCatalogViews.boardStats(all);
        boolean available = termStore.isAvailable();

        // 默认视图是 main；导出那边默认是 all —— 所以默认值由调用方给，这里只认传进来的
        List<Term> filtered = KbCatalogViews.filter(all, q,
                view == null || view.isBlank() ? "main" : view, board);

        int from = Math.max(0, offset);
        if (from >= filtered.size()) {
            return new Page(filtered.size(), List.of(), counts, boards, available);
        }
        int to = Math.min(filtered.size(), from + Math.max(1, limit));
        return new Page(filtered.size(), filtered.subList(from, to), counts, boards, available);
    }

    /** 某个词条（= 一份文档）下的所有文本块。含已下架的（管理端要看得到） */
    public List<ChunkView> chunks(String en) {
        List<ChunkView> out = new ArrayList<>();
        KbBlock b = blockStore.get(en);
        if (b != null) {
            String body = b.body() == null ? "" : b.body();
            out.add(new ChunkView(b.id().value(), body, b.url(), body.length(), b.retired()));
        }
        return out;
    }

    /**
     * 全表读数。批量改状态之后要回给前端刷新进度条 —— 增量更新数字容易算错，
     * 直接重算一遍最省心，代价是一次全块遍历。
     */
    public Counts counts() {
        return KbCatalogViews.count(buildAll());
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
     * <p>顺序是：先按块聚合（这样每个词条自带块数、分类、URL），
     * 再补上词条表里有、但没有任何块的页面。
     */
    private Map<String, Term> buildAll() {
        Map<String, KbTermStore.Entry> terms = termStore.map();
        Map<String, String> rawToGroup = categoryService.rawToGroup();
        Map<String, String> rawToLabel = categoryService.rawToLabel();

        Map<String, Term> out = new LinkedHashMap<>();
        // **一个块 = 一个词条**：词条身份就是块 id，中文名就是块标题。
        // 于是"词条"列表就是**分块的目录** —— 一份文档有多少块，就多出多少条词条。
        for (KbBlock b : blockStore.all()) {
            String key = b.id().value();
            String[] name = nameOf(terms.get(key));
            String zh = name[0].isEmpty() ? safe(b.title()) : name[0];
            String board = KbCatalogViews.boardOf(b.tags(), rawToGroup);
            String body = b.body() == null ? "" : b.body();
            out.put(key, new Term(key, zh, KbCatalogViews.splitAliases(zh), name[1],
                    board, KbCatalogViews.boardLabel(board, rawToLabel), b.tags(),
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
            out.put(e.en(), new Term(e.en(), name[0], KbCatalogViews.splitAliases(name[0]), name[1],
                    board, KbCatalogViews.boardLabel(board, rawToLabel), List.of(), 0, 0, "", false));
        }
        return out;
    }

    /**
     * 词条表的一行 → {@code [中文名, 状态]}。
     *
     * <p>没有中文名时状态一律报 {@code ""} 而不是 {@code draft}：在旧模型里
     * {@code ""} 的意思是"术语表里没有这条"，在新模型里对应的就是"还没起中文名"。
     * 不然那几百个空页面会全部冒充「待核对」，进度条立刻失真。
     */
    private static String[] nameOf(KbTermStore.Entry e) {
        if (e == null || e.zh().isBlank()) {
            return new String[]{"", STATUS_NONE};
        }
        return new String[]{safe(e.zh()), safe(e.status())};
    }

    private static String safe(String s) {
        return s == null ? "" : s.trim();
    }

    // ==================== 跨两半的用例：导入 ====================

    /**
     * 导入一份文档 —— **这是组合层存在的直接理由**。
     *
     * <p>它是一条跨两个真源的用例，顺序不可颠倒：
     * <ol>
     *   <li>块侧解析文档、落块、算向量，并**提出**这批块该登记哪些名字；</li>
     *   <li>名字侧按自己的策略决定哪些真正落库（已有名字的不覆盖）。</li>
     * </ol>
     * 名字挂在块 id 上，所以必须先有块、再登记名字。
     *
     * <p>原先这两步都塞在块侧的导入器里，于是块层依赖名字层 —— 那就是被消掉的那条边。
     * 外部入口现在只有 {@code kb.catalog.KbImportController}，
     * 块层不再对外暴露"只落块"的导入接口，避免有人绕过名字登记
     * （绕过不会报错，只会让中文提问够不到这些页）。
     */
    public ImportOutcome importDocument(String documentText) {
        KbDocImporter.Result blocks = importer.importDocument(documentText);
        int terms = termService.registerMissing(blocks.nameCandidates());
        return new ImportOutcome(blocks, terms);
    }

    /**
     * 导入结果：块侧的结果 + 名字侧登记了几条。
     *
     * <p>为什么分成两半：块层不知道名字登记的结果（它提的是建议），
     * 名字层不知道块的结果。只有组合层同时看得到，所以由它把两半拼给调用方。
     */
    public record ImportOutcome(KbDocImporter.Result blocks, int terms) {
    }

    // ==================== 跨两半的清理 ====================

    /**
     * 清掉**没有对应块**的词条（孤儿行）。
     *
     * <p>这是少数必须同时看两半的动作：判据在块侧，被删的行在名字侧。
     *
     * @return 删掉的条数
     */
    public int deleteOrphanTerms() {
        java.util.Set<com.example.qqbot.kb.EntryId> ids = new java.util.LinkedHashSet<>();
        for (KbBlock b : blockStore.all()) {
            ids.add(b.id());
        }
        return termStore.deleteOrphans(ids);
    }

    // ==================== 导出（给人看的格式）====================

    /** 导出全表 TSV（Excel 旁路）。中间两列由语料现算，不落库 */
    public String exportTsv() {
        return exportTsv("", "", "");
    }

    /**
     * 按**当前视图所见**导出 TSV —— 让「导出待补译名的那批 → Excel 填 → 导回」这条路走得通。
     *
     * <p>自动导入的英文页有几百条没有中文名，在网页上逐条点不现实，
     * 而 {@code POST /terms/import} 本来就收 TSV —— 所以导出必须能只导**当前筛出来的那批**。
     *
     * <p>筛选条件与 {@link #page} 完全共用一份（{@link KbCatalogViews#filter}），
     * 所以「看到什么就导出什么」，不会出现"列表 651 条、导出 3416 条"这种对不上。
     *
     * @param q     搜索词；空 = 不按词筛
     * @param view  见 {@link #VIEWS}；**空 = 全部**（保持老行为，注意与列表默认的 main 不同）
     * @param board 板块 key；空 = 不限
     */
    public String exportTsv(String q, String view, String board) {
        return export(q, view, board, '\t');
    }

    /**
     * 导出 **xlsx 字节** —— 后台默认走这条（见 {@code KbTermXlsx} 里为什么弃用 CSV）。
     *
     * <p>先出 TSV 文本再转，**不重复实现一份导出规则**。
     */
    public byte[] exportXlsx(String q, String view, String board) {
        return com.example.qqbot.kb.term.KbTermXlsx.write(export(q, view, board, '\t'));
    }

    /** 列表与导出共用同一份筛选（{@link KbCatalogViews#filter}）与同一份格式（{@link KbCatalogExport}） */
    private String export(String q, String view, String board, char delim) {
        return KbCatalogExport.tsv(KbCatalogViews.filter(buildAll(), q, view, board), delim);
    }
}
