package com.example.qqbot.kb.catalog;

import com.example.qqbot.kb.CsvTable;
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
 * <h2>为什么用 xlsx 而不是 CSV</h2>
 * CSV 在中文 Windows 上有个**会静默出错**的陷阱：
 * WPS/Excel「另存为 CSV」默认是 **GBK**，而浏览器读文件固定按 **UTF-8** 解码 ——
 * 中文变成乱码，**而且不报错**，直接写坏别名。
 * xlsx 内部就是 UTF-8 的 XML，没有编码这回事。
 *
 * <h2>为什么做成「xlsx ↔ TSV 文本」的适配器</h2>
 * 导入的解析与校验（表头识别、别名合并、已存在不覆盖）只写一份，放在
 * {@link KbCatalog#importTable}。这里只做格式转换，不重复实现导入规则。
 */
public final class EntryXlsx {

    /** 表头。导出时写第一行，导入时靠它认出并跳过 */
    static final List<String> HEADER = List.of("id", "标题", "别名", "分类");

    private EntryXlsx() {
    }

    /** TSV 文本 → xlsx 字节。注释行丢掉，换成真正的表头行 */
    public static byte[] write(String tsvText) {
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
                if (row.get(0).trim().startsWith("#")) {
                    continue;
                }
                if (row.stream().allMatch(v -> v == null || v.isBlank())) {
                    continue;
                }
                Row xr = sheet.createRow(r++);
                for (int i = 0; i < row.size(); i++) {
                    xr.createCell(i).setCellValue(row.get(i) == null ? "" : row.get(i));
                }
            }
            sheet.setColumnWidth(0, 34 * 256);
            sheet.setColumnWidth(1, 46 * 256);
            sheet.setColumnWidth(2, 46 * 256);
            sheet.setColumnWidth(3, 22 * 256);
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
     * <p>认得出表头就跳过它；认不出也照样读 —— 导入侧靠表头找列，
     * 多一行表头会被当成"id=id"的无效行跳过，不会写坏数据。
     */
    public static String read(byte[] bytes) {
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
        return a.equals("id") || a.equals("标题") || a.equals("英文名");
    }
}
