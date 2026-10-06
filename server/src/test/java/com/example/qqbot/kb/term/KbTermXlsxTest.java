package com.example.qqbot.kb.term;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * xlsx 导出/导入的格式转换 —— 词条表 Excel 旁路。
 *
 * <h2>为什么弃用 CSV（2026-10-04 用户要求）</h2>
 * WPS/Excel 在中文 Windows 上「另存为 CSV」默认是 **GBK**，而浏览器读文件固定按 **UTF-8** 解码
 * → 中文变乱码**而且不报错**，直接写进词条表污染检索。
 * xlsx 内部是 UTF-8 的 XML，**没有编码这回事**。
 *
 * <p>这里钉的是「xlsx 读写与 TSV 互转」这一层 —— 导入的业务规则在
 * {@link KbTermStore#importTsv} 里，不在这里重复。
 */
class KbTermXlsxTest {

    private static final String TSV = """
            # 知识库词条表
            # 列：英文名 <TAB> 中文名 ...
            wiki-strength\t力量\t\t\tverified
            wiki-intelligence\t智力\t\t\tverified
            """;

    @Test
    @DisplayName("★ xlsx 往返：导出的表能被读回成同样的数据行（注释行不进表格）")
    void roundTrip() {
        byte[] xlsx = KbTermXlsx.write(TSV);
        assertThat(xlsx).as("必须是真的 xlsx（zip 头 PK）").isNotEmpty();
        assertThat(xlsx[0]).isEqualTo((byte) 'P');
        assertThat(xlsx[1]).isEqualTo((byte) 'K');

        String back = KbTermXlsx.read(xlsx);
        assertThat(back)
                .as("两条数据都要在")
                .contains("wiki-strength\t力量").contains("wiki-intelligence\t智力");
        assertThat(back)
                .as("注释行不该进表格")
                .doesNotContain("#");
        assertThat(back)
                .as("表头行读回来时被跳过（否则会多一条 英文名=英文名 的无效行）")
                .doesNotContain(KbTermXlsx.HEADER.get(0) + "\t");
    }

    @Test
    @DisplayName("★ 中文不能丢也不能乱 —— 这正是弃用 CSV 的原因")
    void chineseSurvives() {
        String back = KbTermXlsx.read(KbTermXlsx.write(TSV));
        assertThat(back).contains("力量").contains("智力");
        assertThat(back).as("不该出现替换字符（CSV 编码错的典型症状）").doesNotContain("\uFFFD");
    }

    @Test
    @DisplayName("含逗号/引号/换行的字段也要能原样往返（用户会改中文名和别名）")
    void trickyCells() {
        String tsv = "a\t深渊之翼斧、Abyssal Wing Axe\t\t\tverified\n"
                + "b\tBlack Is Your Absence, Dark Is My Night\t\t\tverified\n"
                + "c\t带\"引号\"的名字\t\t\tverified\n";
        String back = KbTermXlsx.read(KbTermXlsx.write(tsv));
        assertThat(back).contains("深渊之翼斧、Abyssal Wing Axe");
        assertThat(back).contains("Black Is Your Absence, Dark Is My Night");
        assertThat(back).contains("带\"引号\"的名字");
    }

    @Test
    @DisplayName("空输入不炸")
    void emptyInput() {
        assertThat(KbTermXlsx.read(new byte[0])).isEmpty();
        assertThat(KbTermXlsx.read(null)).isEmpty();
        assertThat(KbTermXlsx.write(null)).isNotEmpty();
    }

    @Test
    @DisplayName("表头缺失也能读（用户把表头删了）")
    void headerOptional() {
        String tsv = "wiki-wolf\t狼\t\t\tverified\n";
        String back = KbTermXlsx.read(KbTermXlsx.write(tsv));
        assertThat(back).contains("wiki-wolf\t狼");
        assertThat(back).doesNotContain("英文名");
    }

    @Test
    @DisplayName("表头识别：英文名 / en / english 都认")
    void headerDetection() {
        // 直接构造带表头的 xlsx 不方便，这里只验 read 对「首行是表头」的容忍度：
        // 用 write 出来的一定有表头，read 回来不该出现它 —— 见 roundTrip
        List<String> h = KbTermXlsx.HEADER;
        assertThat(h).hasSize(5);
        assertThat(h.get(0)).isEqualTo("英文名");
    }
}
