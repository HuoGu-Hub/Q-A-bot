package com.example.qqbot.kb.catalog;

import com.example.qqbot.kb.CsvTable;
import com.example.qqbot.kb.EntryId;
import com.example.qqbot.kb.TermNames;
import com.example.qqbot.kb.block.KbBlock;
import com.example.qqbot.kb.block.KbBlockStore;
import com.example.qqbot.kb.block.KbDocImporter;
import com.example.qqbot.kb.category.CategoryService;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * 知识库词条的**读模型** —— 一个词条就是一个分块。
 *
 * <h2>为什么它这么薄</h2>
 * 改造前这里要同时拼「块表」与「词条名字表」两半，所以需要一个组合层。
 * 现在名字（标题 + 别名）就在块上，两半合成了一半：本类只负责
 * 筛选、统计、导出/导入别名这几件事。
 *
 * <h2>「词条」就是一个分块</h2>
 * {@code id + 标题 + 别名 + 分类}，与从 wiki 清洗下来的语料同一个形状，
 * 只是多了一个别名。别名里没单独写过的那部分由标题「（English）」派生。
 */
@Component
public class KbCatalog {

    /** 视图预设。前端只传这一个参数，语义全在这里 */
    public static final List<String> VIEWS = List.of("all", "aliased", "unaliased", "uncategorized");

    private final KbBlockStore blockStore;
    private final CategoryService categoryService;
    private final KbDocImporter importer;

    public KbCatalog(KbBlockStore blockStore, CategoryService categoryService, KbDocImporter importer) {
        this.blockStore = blockStore;
        this.categoryService = categoryService;
        this.importer = importer;
    }

    /**
     * 一条词条。
     *
     * @param alias        别名（存库那份为空时用标题派生兜底）
     * @param categories   认得的分类名（空表 = 未分类）
     * @param uncategorized 是不是未分类
     */
    public record Entry(String id, String docId, String title, String alias, List<String> categories,
                        String source, int chars, String url, boolean retired, boolean uncategorized) {
    }

    /** 全表读数。刻意不受搜索/筛选影响 —— 它是"全库有多少条"的口径 */
    public record Counts(int all, int aliased, int unaliased, int uncategorized, int retired) {
    }

    /** 一页列表 */
    public record Page(int total, List<Entry> items, Counts counts,
                       List<CategoryService.Stat> categories, int uncategorizedCount,
                       boolean available) {
    }

    /** 别名表导入结果 */
    public record ImportResult(int updated, int skipped) {
    }

    // ==================== 读 ====================

    public Page page(String q, String view, String category, int limit, int offset) {
        List<Entry> all = filtered(q, view, category);
        int from = Math.max(0, offset);
        List<Entry> items = from >= all.size()
                ? List.of()
                : new ArrayList<>(all.subList(from, Math.min(all.size(), from + Math.max(1, limit))));
        return new Page(all.size(), items, counts(), categoryService.stats(),
                categoryService.uncategorizedCount(), blockStore.isAvailable());
    }

    public Counts counts() {
        int aliased = 0;
        int unaliased = 0;
        int uncategorized = 0;
        int retired = 0;
        int all = 0;
        for (KbBlock b : blockStore.all()) {
            all++;
            if (b.retired()) {
                retired++;
            }
            if (TermNames.effectiveAlias(b.title(), b.aliasOrEmpty()).isBlank()) {
                unaliased++;
            } else {
                aliased++;
            }
            if (categoryService.recognized(b.tags()).isEmpty()) {
                uncategorized++;
            }
        }
        return new Counts(all, aliased, unaliased, uncategorized, retired);
    }

    /** 按 id 取一条 */
    public Entry get(String id) {
        KbBlock b = blockStore.get(EntryId.of(id));
        return b == null ? null : entryOf(b);
    }

    private Entry entryOf(KbBlock b) {
        List<String> cats = categoryService.recognized(b.tags());
        String body = b.body() == null ? "" : b.body();
        return new Entry(b.id().value(), b.docId(), b.title(),
                TermNames.effectiveAlias(b.title(), b.aliasOrEmpty()), cats,
                b.source(), body.length(), b.url(), b.retired(), cats.isEmpty());
    }

    /**
     * 列表与导出**共用**的筛选 —— 保证「看到什么就导出什么」。
     *
     * @param view 见 {@link #VIEWS}；空 = all
     * @param category 分类名；空 = 不限；{@link CategoryService#UNCATEGORIZED} = 只看未分类
     */
    private List<Entry> filtered(String q, String view, String category) {
        String needle = q == null ? "" : q.trim().toLowerCase(Locale.ROOT);
        String v = view == null || view.isBlank() ? "all" : view.trim().toLowerCase(Locale.ROOT);
        String cat = category == null ? "" : category.trim();

        List<Entry> out = new ArrayList<>();
        for (KbBlock b : blockStore.all()) {
            Entry e = entryOf(b);
            if ("aliased".equals(v) && e.alias().isBlank()) {
                continue;
            }
            if ("unaliased".equals(v) && !e.alias().isBlank()) {
                continue;
            }
            if ("uncategorized".equals(v) && !e.uncategorized()) {
                continue;
            }
            if (!cat.isEmpty()) {
                if (CategoryService.UNCATEGORIZED.equals(cat)) {
                    if (!e.uncategorized()) {
                        continue;
                    }
                } else if (!e.categories().contains(cat)) {
                    continue;
                }
            }
            if (!needle.isEmpty()) {
                String hay = (nz(e.id()) + " " + nz(e.title()) + " " + nz(e.alias()) + " " + nz(e.docId()))
                        .toLowerCase(Locale.ROOT);
                if (!hay.contains(needle)) {
                    continue;
                }
            }
            out.add(e);
        }
        // 按标题排序：列表要能翻页、导出要能对账，顺序必须稳定
        out.sort(Comparator.comparing(Entry::title, Comparator.nullsFirst(String.CASE_INSENSITIVE_ORDER)));
        return out;
    }

    // ==================== 导入 ====================

    /**
     * 导入一份文档 —— 落块（**自动切割成一个个单独的词条**）。
     *
     * <p>别名不会被导入覆盖：那是人工成果。
     */
    public KbDocImporter.Result importDocument(String documentText) {
        return importer.importDocument(documentText);
    }

    // ==================== 导出 / 导入别名表 ====================

    /** 导出当前视图为 TSV（列：id / 标题 / 别名 / 分类） */
    public String exportTsv(String q, String view, String category) {
        StringBuilder sb = new StringBuilder(TSV_HEADER);
        for (Entry e : filtered(q, view, category)) {
            String cat = e.categories().isEmpty() ? CategoryService.UNCATEGORIZED : e.categories().get(0);
            sb.append(cell(e.id())).append('\t')
                    .append(cell(e.title())).append('\t')
                    .append(cell(e.alias())).append('\t')
                    .append(cell(cat)).append('\n');
        }
        return sb.toString();
    }

    private static final String TSV_HEADER = """
            # 知识库词条表 —— 标题与别名
            # 列：id <TAB> 标题 <TAB> 别名 <TAB> 分类
            # 别名可以写多个，用「、」分隔；标题与分类是导出时算出来的，改了没用
            # 导入时只按 id 覆盖【别名】一列 —— 别名可以随便改，标题/分类不会被动
            """;

    /** 导出 xlsx 字节 —— 先出 TSV 文本再转，不重复实现一份规则 */
    public byte[] exportXlsx(String q, String view, String category) {
        return EntryXlsx.write(exportTsv(q, view, category));
    }

    /**
     * 从 TSV / CSV / xlsx 导入别名表。
     *
     * <p>只认 {@code id} 与 {@code 别名} 两列；标题与分类是给人看的派生信息，一律忽略。
     * 找不到对应块的 id 计入 {@code skipped}（不报错、不新建块 —— 词条只能由导入文档产生）。
     */
    public ImportResult importTable(String text) {
        if (text == null || text.isBlank()) {
            return new ImportResult(0, 0);
        }
        char delim = CsvTable.sniffDelimiter(text);
        List<List<String>> rows = CsvTable.parse(text, delim);

        int iId = -1;
        int iAlias = -1;
        int headerAt = -1;
        for (int i = 0; i < Math.min(rows.size(), 5); i++) {
            int a = col(rows.get(i), "id", "块id", "词条id", "en", "english");
            int b = col(rows.get(i), "别名", "别称", "alias", "aliases", "其他译名");
            if (a >= 0 && b >= 0) {
                iId = a;
                iAlias = b;
                headerAt = i;
                break;
            }
        }
        if (headerAt < 0) {
            return new ImportResult(0, 0);
        }

        int updated = 0;
        int skipped = 0;
        for (int i = headerAt + 1; i < rows.size(); i++) {
            List<String> r = rows.get(i);
            if (r.isEmpty() || (r.size() == 1 && r.get(0).isBlank())) {
                continue;
            }
            if (cell(r, 0).startsWith("#")) {
                continue;
            }
            String id = cell(r, iId);
            if (id.isEmpty()) {
                skipped++;
                continue;
            }
            EntryId eid = EntryId.of(id);
            if (blockStore.get(eid) == null) {
                skipped++;
                continue;
            }
            if (blockStore.updateAlias(eid, mergeAliases(cell(r, iAlias)))) {
                updated++;
            }
        }
        return new ImportResult(updated, skipped);
    }

    /** 别名统一成「、」分隔并去重 */
    private static String mergeAliases(String alias) {
        String extra = alias == null ? "" : alias.trim();
        if (extra.isEmpty()) {
            return "";
        }
        extra = extra.replace(',', '、').replace('，', '、')
                .replace('/', '、').replace('|', '、').replace('｜', '、');
        java.util.LinkedHashSet<String> parts = new java.util.LinkedHashSet<>();
        for (String p : extra.split("、")) {
            String t = p.trim();
            if (!t.isEmpty()) {
                parts.add(t);
            }
        }
        return String.join("、", parts);
    }

    private static int col(List<String> header, String... names) {
        if (header == null) {
            return -1;
        }
        for (int i = 0; i < header.size(); i++) {
            String h = header.get(i) == null ? "" : header.get(i).trim().toLowerCase(Locale.ROOT).replace(" ", "");
            for (String n : names) {
                if (h.equals(n)) {
                    return i;
                }
            }
        }
        return -1;
    }

    private static String cell(List<String> row, int idx) {
        if (idx < 0 || idx >= row.size()) {
            return "";
        }
        String s = row.get(idx);
        return s == null ? "" : s.trim();
    }

    /** RFC4180 转义：含分隔符/引号/换行时用双引号包住，内部引号翻倍 */
    private static String cell(String v) {
        String s = v == null ? "" : v;
        if (s.indexOf('\t') < 0 && s.indexOf('"') < 0 && s.indexOf('\n') < 0 && s.indexOf('\r') < 0) {
            return s;
        }
        return '"' + s.replace("\"", "\"\"") + '"';
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
