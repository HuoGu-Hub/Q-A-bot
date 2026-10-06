package com.example.qqbot.kb.term;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;

/**
 * 词条表的 **xlsx 读写** —— 后台导入导出的 Excel 旁路。
 *
 * <h2>为什么不用 CSV（2026-10-04 用户明确要求换成 xlsx）</h2>
 * CSV 在中文 Windows 上有个**会静默出错**的陷阱：
 * WPS/Excel「另存为 CSV」默认是 **GBK**，而浏览器读文件固定按 **UTF-8** 解码 ——
 * 中文变成乱码，**而且不报错**，直接写进词条表污染检索。
 *
 * <p>xlsx 内部就是 UTF-8 的 XML，**没有编码这回事**；而且现在没人用 CSV 了。
 * 代价是多一个约 10MB 的依赖（Apache POI）。
 *
 * <h2>为什么做成「xlsx ↔ TSV 文本」的适配器</h2>
 * 词条导入的校验逻辑（key 唯一性、状态合法性、已存在不覆盖、BOM/乱码防护…）
 * 已经在 {@link KbTermStore#importTsv} 里了，而且有测试。
 * 这里只做格式转换，**不重复实现一遍导入规则** —— 两份规则必然漂移。
 *
 * <p>所以：导出 = {@code TSV → xlsx 字节}；导入 = {@code xlsx 字节 → TSV 文本 → 走原路径}。
 */
final class KbTermXlsx {

    private KbTermXlsx() {
    }

    /** 表头。导出时写第一行，导入时靠它认出并跳过 */
    static final List<String> HEADER = List.of("英文名", "中文名", "wiki页面", "类别", "状态");

    /**
     * TSV 文本 → xlsx 字节。
     *
     * <p>注释行（以 {@code #} 开头）**丢掉** —— 表格里不该出现它们；
     * 取而代之的是真正的**表头行**，在 Excel/WPS 里看着才像张表。
     */
    static byte[] write(String tsvText) {
        List<List<String>> rows = CsvTable.parse(tsvText == null ? "" : tsvText, '\t');
        try (Workbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet("词条");

            CellStyle head = wb.createCellStyle();
            Font bold = wb.createFont();
            bold.setBold(true);
            head.setFont(bold);

            int r = 0;
            Row hr = sheet.createRow(r++);
            for (int i = 0; i < HEADER.size(); i++) {
                Cell c = hr.createCell(i);
                c.setCellValue(HEADER.get(i));
                c.setCellStyle(head);
            }
            for (List<String> row : rows) {
                if (row.isEmpty()) {
                    continue;
                }
                String first = row.get(0).trim();
                // 注释行不进表格
                if (first.startsWith("#")) {
                    continue;
                }
                // 空行（整行没内容）也不进
                boolean allBlank = row.stream().allMatch(v -> v == null || v.isBlank());
                if (allBlank) {
                    continue;
                }
                Row xr = sheet.createRow(r++);
                for (int i = 0; i < row.size(); i++) {
                    xr.createCell(i).setCellValue(row.get(i) == null ? "" : row.get(i));
                }
            }
            // 列宽：中文名那列留宽点，否则打开就得手动拉
            sheet.setColumnWidth(0, 40 * 256);
            sheet.setColumnWidth(1, 46 * 256);
            sheet.setColumnWidth(2, 40 * 256);
            sheet.setColumnWidth(3, 14 * 256);
            sheet.setColumnWidth(4, 12 * 256);
            sheet.createFreezePane(0, 1);

            wb.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * xlsx 字节 → TSV 文本（制表符分隔）。
     *
     * <p>认得出表头就跳过它；认不出（比如用户把表头删了）也照样读 ——
     * 导入侧只认第 1/2/5 列，多一行表头会被当成"英文名=英文名"的无效行跳过，
     * 不会写坏数据。
     */
    static String read(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        DataFormatter fmt = new DataFormatter();
        try (Workbook wb = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
            Sheet sheet = wb.getSheetAt(0);
            if (sheet == null) {
                return "";
            }
            boolean first = true;
            for (Row row : sheet) {
                List<String> cells = new ArrayList<>();
                int last = row.getLastCellNum();
                for (int i = 0; i < Math.max(last, 0); i++) {
                    Cell c = row.getCell(i);
                    cells.add(c == null ? "" : fmt.formatCellValue(c).trim());
                }
                while (!cells.isEmpty() && cells.get(cells.size() - 1).isEmpty()) {
                    cells.remove(cells.size() - 1);
                }
                if (cells.isEmpty()) {
                    continue;
                }
                if (first) {
                    first = false;
                    if (isHeader(cells)) {
                        continue;
                    }
                }
                sb.append(String.join("\t", cells)).append('\n');
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return sb.toString();
    }

    /** 第一行是不是表头 */
    private static boolean isHeader(List<String> cells) {
        String a = cells.get(0).trim().toLowerCase();
        return a.equals("英文名") || a.equals("en") || a.equals("english");
    }
}
