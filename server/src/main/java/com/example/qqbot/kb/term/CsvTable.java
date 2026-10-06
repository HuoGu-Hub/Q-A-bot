package com.example.qqbot.kb.term;

import java.util.ArrayList;
import java.util.List;

/**
 * 极小的分隔符文本解析器 —— 够用就好，但**必须对**。
 *
 * <h2>为什么不能简单 split(",")</h2>
 * 术语表是**从 Excel 另存为 CSV 出来的**，而 Excel 一定会做两件事：
 * <ol>
 *   <li>字段里含逗号时**加双引号**：{@code Kiln,熔炉,"炉子,窑",verified} ——
 *       直接 split 会把一个别名拆成两个字段，而且**不报错**；</li>
 *   <li>字段里含换行时**用引号包住整段**，于是"一行"会跨物理行。</li>
 * </ol>
 * 这两种情况在术语表里都会真实出现（别名本来就常用逗号分隔），
 * 所以这里按 RFC4180 老老实实解析，而不是图快 split。
 */
final class CsvTable {

    private CsvTable() {
    }

    /**
     * 猜分隔符。
     *
     * <p>看第一行有内容的行里谁多：制表符还是逗号。Excel 另存为 CSV 是逗号，
     * 而我们自己导出的旧文件是制表符 —— 两种都有人用，所以自动认。
     */
    static char sniffDelimiter(String text) {
        if (text == null) {
            return '\t';
        }
        for (String line : text.split("\r?\n", 8)) {
            String s = line.trim();
            if (s.isEmpty() || s.charAt(0) == '#') {
                continue;
            }
            int tabs = count(s, '\t');
            int commas = count(s, ',');
            return tabs >= commas ? '\t' : ',';
        }
        return '\t';
    }

    private static int count(String s, char c) {
        int n = 0;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == c) {
                n++;
            }
        }
        return n;
    }

    /** 解析成 行 × 列。支持双引号包裹、{@code ""} 转义、以及引号内的换行 */
    static List<List<String>> parse(String text, char delim) {
        List<List<String>> rows = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return rows;
        }
        List<String> row = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean quoted = false;

        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < text.length() && text.charAt(i + 1) == '"') {
                        cell.append('"');
                        i++;
                    } else {
                        quoted = false;
                    }
                } else {
                    cell.append(c);
                }
            } else if (c == '"' && cell.length() == 0) {
                // ⚠️ **只有字段开头**的引号才开启引号模式（RFC4180）。
                //    原来这里不判断位置，于是字段中间一个游离的 " 就会开启引号模式，
                //    把后面的制表符和换行**全部吞进同一个字段** ——
                //    用户在表格里打错一个引号，整份导入就全乱，而且不报错。
                //    实测踩到：TSV "带\"引号\"的名字" 被解析成 "带引号的名字"（引号丢了）。
                quoted = true;
            } else if (c == delim) {
                row.add(cell.toString());
                cell.setLength(0);
            } else if (c == '\n') {
                row.add(cell.toString());
                cell.setLength(0);
                rows.add(row);
                row = new ArrayList<>();
            } else if (c != '\r') {
                cell.append(c);
            }
        }
        if (cell.length() > 0 || !row.isEmpty()) {
            row.add(cell.toString());
            rows.add(row);
        }
        return rows;
    }
}
