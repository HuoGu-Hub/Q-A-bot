package com.example.qqbot.kb.catalog;

import com.example.qqbot.kb.term.KbTermXlsx;

import java.util.List;

/**
 * 词条表的导出格式 —— TSV 文本与 xlsx 字节。
 *
 * <h2>为什么单独一个类</h2>
 * "给人看的格式"是这个系统里最容易变、也最容易改错的一块：
 * 列的个数、列的含义、转义规则，全在这里。它原先和"读语料 / 写名字"挤在一起，
 * 改一个列宽要在几百行里找。它导出的是组合层的目录视图，所以归属组合层。
 *
 * <h2>为什么必须转义</h2>
 * 中文名里本来就可能含逗号与引号（别名常用「、」和逗号分隔）。
 * 不转义的话 CSV 会被切错列，而且<b>不报错</b> —— 导入回来就是一批错名字。
 * 所以单元格一律按 RFC4180 处理：含分隔符/引号/换行时用双引号包住，内部引号翻倍。
 */
final class KbCatalogExport {

    private KbCatalogExport() {
    }

    /** 5 列 TSV 的表头。导入时只认第 1/2/5 列，中间两列是给人看的 */
    static final String TSV_HEADER = """
            # 知识库词条表 —— 中文名与核对状态
            # 列：英文名 <TAB> 中文名 <TAB> wiki页面 <TAB> 类别 <TAB> 状态
            # 状态：draft = 机器初稿未核对 ｜ verified = 已人工核对 ｜ rejected = 不要用
            # 中文名可以写多个别名，用「、」分隔
            # ⚠️ 导入时只读第 1、2、5 列；中间两列是导出时算出来的派生信息，改了没用
            """;

    /**
     * 把已经筛好的词条渲染成 5 列文本。
     *
     * <p>筛选不在这里做 —— 顺序与筛选条件由 {@link KbCatalogViews#filter} 与
     * 调用方决定，这样"看到什么就导出什么"只有一条链路。
     *
     * @param delim 列分隔符，{@code '\t'} 给 TSV 用
     */
    static String tsv(List<KbCatalog.Term> sorted, char delim) {
        StringBuilder sb = new StringBuilder(TSV_HEADER);
        for (KbCatalog.Term t : sorted) {
            if (t.zh().isEmpty() && t.chunkCount() == 0) {
                continue;   // 既没名字又没正文的空页面不进导出，免得淹掉有用的行
            }
            sb.append(cell(t.en(), delim)).append(delim)
                    .append(cell(t.zh(), delim)).append(delim)
                    .append(cell(t.en().replace(' ', '_'), delim)).append(delim)
                    .append(cell(t.cats().isEmpty() ? "" : t.cats().get(0), delim)).append(delim)
                    .append(cell(t.status().isEmpty() ? "draft" : t.status(), delim)).append('\n');
        }
        return sb.toString();
    }

    /** xlsx 字节 —— 先出 TSV 文本再转，不重复实现一份导出规则（见 {@link KbTermXlsx}） */
    static byte[] xlsx(List<KbCatalog.Term> sorted, char delim) {
        return KbTermXlsx.write(tsv(sorted, delim));
    }

    /** 按 RFC4180 转义一个单元格：含分隔符/引号/换行时用双引号包住，内部引号翻倍 */
    private static String cell(String v, char delim) {
        String s = v == null ? "" : v;
        if (s.indexOf(delim) < 0 && s.indexOf('"') < 0 && s.indexOf('\n') < 0 && s.indexOf('\r') < 0) {
            return s;
        }
        return '"' + s.replace("\"", "\"\"") + '"';
    }
}
