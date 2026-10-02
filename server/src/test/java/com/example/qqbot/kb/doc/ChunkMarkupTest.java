package com.example.qqbot.kb.doc;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 分块标记解析器的验收测试。
 *
 * <p>它钉的是**约定本身**：格式宽容到什么程度、转义怎么算、什么算致命错误、
 * 什么只算提醒。这些细节一旦走样，表现是"导入进来少了几块"——
 * 不会报错、只会静默丢内容，所以必须逐条断言。
 */
class ChunkMarkupTest {

    /** 一个反斜杠。转义相关的用例全靠它，直接写字符串字面量太容易数错 */
    private static final String BS = "\\";

    private static String doc(String... lines) {
        return String.join("\n", lines);
    }

    /* ==================== 基本格式 ==================== */

    @Test
    @DisplayName("基本解析：标题在分隔符里，id 可选，正文归到块里")
    void parsesBasicDocument() {
        ChunkMarkup.Parsed p = ChunkMarkup.parse(doc(
                "=== 熔炉 === <!-- id: kiln -->",
                "熔炉用来烧制。",
                "",
                "== 灵石 ==",
                "灵石是货币。"));

        assertThat(p.ok()).as(p.errors().toString()).isTrue();
        assertThat(p.blocks()).hasSize(2);

        ChunkMarkup.Block a = p.blocks().get(0);
        assertThat(a.id()).isEqualTo("kiln");
        assertThat(a.key()).as("有 id 时以 id 为准").isEqualTo("kiln");
        assertThat(a.title()).isEqualTo("熔炉");
        assertThat(a.body()).isEqualTo("熔炉用来烧制。");
        assertThat(a.line()).isEqualTo(1);
        assertThat(a.idMissing()).isFalse();

        ChunkMarkup.Block b = p.blocks().get(1);
        assertThat(b.id()).isNull();
        assertThat(b.key()).as("没 id 时退回用标题当身份").isEqualTo("灵石");
        assertThat(b.body()).isEqualTo("灵石是货币。");
        assertThat(b.line()).isEqualTo(4);
        assertThat(b.idMissing()).isTrue();
    }

    @Test
    @DisplayName("等号个数宽容：= 到 ======== 都认")
    void toleratesAnyEqualsCount() {
        ChunkMarkup.Parsed p = ChunkMarkup.parse(doc(
                "= 一 =",
                "甲",
                "== 二 ==",
                "乙",
                "======== 三 ========",
                "丙"));

        assertThat(p.ok()).as(p.errors().toString()).isTrue();
        assertThat(p.blocks()).extracting(ChunkMarkup.Block::title)
                .containsExactly("一", "二", "三");
    }

    @Test
    @DisplayName("★ 左右等号数量不一致 → 不是分隔符，当普通正文")
    void mismatchedEqualsIsNotDelimiter() {
        ChunkMarkup.Parsed p = ChunkMarkup.parse(doc(
                "=== 甲 ===",
                "正文一",
                "=== 乙 ==",
                "继续"));

        assertThat(p.ok()).isTrue();
        assertThat(p.blocks()).as("只有「甲」这一块").hasSize(1);
        assertThat(p.blocks().get(0).body())
                .as("不匹配的那行必须留在正文里，不能凭空多出一块")
                .contains("=== 乙 ==")
                .contains("继续");
    }

    @Test
    @DisplayName("标题自动去掉前后空格")
    void titleIsTrimmed() {
        ChunkMarkup.Parsed p = ChunkMarkup.parse(doc("===    多余空格   ===", "正文"));

        assertThat(p.blocks().get(0).title()).isEqualTo("多余空格");
    }

    /* ==================== id 的规则 ==================== */

    @Test
    @DisplayName("★ id 只允许英文、数字、-；含别的字符要报错（而且报的是人看得懂的话）")
    void invalidIdCharsIsError() {
        ChunkMarkup.Parsed p = ChunkMarkup.parse(doc("=== 标题 === <!-- id: 熔炉 -->", "正文"));

        assertThat(p.ok()).isFalse();
        assertThat(p.errors().get(0))
                .contains("id 只能由英文字母、数字和 - 组成")
                .contains("熔炉");
        assertThat(p.blocks()).hasSize(1);
        assertThat(p.blocks().get(0).id()).as("非法 id 不采纳").isNull();
    }

    @Test
    @DisplayName("★ id 重复 → 致命错误（同一份文档内）")
    void duplicateIdIsError() {
        ChunkMarkup.Parsed p = ChunkMarkup.parse(doc(
                "=== 甲 === <!-- id: same -->",
                "正文一",
                "=== 乙 === <!-- id: same -->",
                "正文二"));

        assertThat(p.ok()).isFalse();
        assertThat(p.errors().get(0)).contains("重复").contains("same");
    }

    @Test
    @DisplayName("★ 没写 id 的两个块标题相同 → 也是致命错误（因为身份退回了标题）")
    void duplicateTitleWithoutIdIsError() {
        ChunkMarkup.Parsed p = ChunkMarkup.parse(doc(
                "=== 同名 ===",
                "正文一",
                "=== 同名 ===",
                "正文二"));

        assertThat(p.ok()).isFalse();
        assertThat(p.errors().get(0)).contains("重复").contains("没写 id");
    }

    @Test
    @DisplayName("★ 有各自的 id、但标题相同 → 只提醒，不阻断（真实 Wiki 语料就是这种形状）")
    void duplicateTitleWithDistinctIdsIsOnlyWarning() {
        ChunkMarkup.Parsed p = ChunkMarkup.parse(doc(
                "=== Forging The Path Update === <!-- id: p-1 -->",
                "块一",
                "=== Forging The Path Update === <!-- id: p-2 -->",
                "块二"));

        assertThat(p.ok()).as("不能因为标题重复就整份导入失败：" + p.errors()).isTrue();
        assertThat(p.blocks()).hasSize(2);
        assertThat(p.warnings().stream().anyMatch(w -> w.contains("标题") && w.contains("重复")))
                .as("但要让作者知道：" + p.warnings()).isTrue();
    }

    /* ==================== 转义 ==================== */

    @Test
    @DisplayName("★ 行首加反斜杠 → 那一行变回普通正文，不产生块")
    void escapedDelimiterIsLiteral() {
        ChunkMarkup.Parsed p = ChunkMarkup.parse(doc(
                "=== 真块 ===",
                "正文一",
                BS + "=== 看起来像标题 ===",
                "正文二"));

        assertThat(p.ok()).as(p.errors().toString()).isTrue();
        assertThat(p.blocks()).hasSize(1);
        assertThat(p.blocks().get(0).body())
                .contains("=== 看起来像标题 ===")
                .as("转义用的反斜杠本身不该出现在正文里")
                .doesNotContain(BS + "=== 看起来像标题");
        assertThat(p.blocks().get(0).body()).contains("正文二");
    }

    @Test
    @DisplayName("★ 标题里的 = 用反斜杠转义")
    void escapedEqualsInTitle() {
        ChunkMarkup.Parsed p = ChunkMarkup.parse(doc(
                "=== a " + BS + "= b ===",
                "正文"));

        assertThat(p.ok()).as(p.errors().toString()).isTrue();
        assertThat(p.blocks().get(0).title()).isEqualTo("a = b");
    }

    /* ==================== 文档头 ==================== */

    @Test
    @DisplayName("★ 文档头：url 和 tags 有地方放，而且不算「第一个分隔符之前的内容」")
    void parsesDocHeader() {
        ChunkMarkup.Parsed p = ChunkMarkup.parse(doc(
                "<!-- doc",
                "name: Flame Altar",
                "source: wiki",
                "url: https://enshrouded.wiki.gg/wiki/Flame_Altar",
                "tags: Essentials, Stations",
                "-->",
                "",
                "=== 灵火祭坛 === <!-- id: altar -->",
                "祭坛是复活点。"));

        assertThat(p.ok()).as(p.errors().toString()).isTrue();
        assertThat(p.meta("name")).isEqualTo("Flame Altar");
        assertThat(p.meta("url")).isEqualTo("https://enshrouded.wiki.gg/wiki/Flame_Altar");
        assertThat(ChunkMarkup.splitTags(p.meta("tags"))).containsExactly("Essentials", "Stations");
        assertThat(p.preamble()).as("文档头不该被当成前言").isEmpty();
        assertThat(p.warnings().stream().noneMatch(w -> w.contains("第一个分隔符之前"))).isTrue();
        assertThat(p.blocks()).hasSize(1);
        assertThat(p.blocks().get(0).id()).isEqualTo("altar");
    }

    @Test
    @DisplayName("没有文档头 → 空表，解析行为不变")
    void headerIsOptional() {
        ChunkMarkup.Parsed p = ChunkMarkup.parse(doc("=== 甲 ===", "正文"));

        assertThat(p.ok()).isTrue();
        assertThat(p.header()).isEmpty();
        assertThat(p.meta("url")).isEmpty();
    }

    @Test
    @DisplayName("未知字段原样保留（外部工具多给的元数据不该丢）")
    void unknownHeaderKeysAreKept() {
        ChunkMarkup.Parsed p = ChunkMarkup.parse(doc(
                "<!-- doc",
                "name: 甲",
                "crawled-at: 2026-09-28",
                "-->",
                "=== 甲 ===",
                "正文"));

        assertThat(p.ok()).isTrue();
        assertThat(p.meta("crawled-at")).isEqualTo("2026-09-28");
    }

    @Test
    @DisplayName("★ 文档头没闭合 → 致命错误（否则整个文件会被当成前言吞掉）")
    void unclosedHeaderIsError() {
        ChunkMarkup.Parsed p = ChunkMarkup.parse(doc(
                "<!-- doc",
                "name: 甲",
                "=== 甲 ===",
                "正文"));

        assertThat(p.ok()).isFalse();
        assertThat(p.errors().get(0)).contains("文档头没有闭合");
    }

    @Test
    @DisplayName("正文里偶然出现的 HTML 注释不会被当成文档头")
    void randomCommentIsNotHeader() {
        ChunkMarkup.Parsed p = ChunkMarkup.parse(doc(
                "<!-- 这只是正文里的注释 -->",
                "=== 甲 ===",
                "正文"));

        assertThat(p.ok()).isTrue();
        assertThat(p.header()).isEmpty();
        assertThat(p.preamble()).contains("这只是正文里的注释");
    }

    /* ==================== 边界 ==================== */

    @Test
    @DisplayName("第一个分隔符之前的内容单独保留，并提醒（绝不静默丢弃）")
    void preambleIsPreserved() {
        ChunkMarkup.Parsed p = ChunkMarkup.parse(doc(
                "这是文档开头的说明",
                "",
                "=== 甲 ===",
                "正文"));

        assertThat(p.ok()).isTrue();
        assertThat(p.preamble()).isEqualTo("这是文档开头的说明");
        assertThat(p.warnings().stream().anyMatch(w -> w.contains("第一个分隔符之前"))).isTrue();
    }

    @Test
    @DisplayName("正文为空的块 → 提醒（不是错误）")
    void emptyBodyIsWarning() {
        ChunkMarkup.Parsed p = ChunkMarkup.parse(doc(
                "=== 甲 ===",
                "有正文",
                "=== 乙 ===",
                "=== 丙 ===",
                "有正文"));

        assertThat(p.ok()).isTrue();
        assertThat(p.warnings().stream().anyMatch(w -> w.contains("正文是空的") && w.contains("乙"))).isTrue();
    }

    @Test
    @DisplayName("整份文档一个分隔符都没有 → 致命错误（否则会静默导入 0 块）")
    void noDelimiterIsError() {
        ChunkMarkup.Parsed p = ChunkMarkup.parse("就是一整段普通文字，没有任何标记");

        assertThat(p.ok()).isFalse();
        assertThat(p.errors().get(0)).contains("没有找到任何分块分隔符");
        assertThat(p.blocks()).isEmpty();
    }

    @Test
    @DisplayName("空文档 → 致命错误")
    void emptyDocumentIsError() {
        assertThat(ChunkMarkup.parse("").ok()).isFalse();
        assertThat(ChunkMarkup.parse(null).ok()).isFalse();
        assertThat(ChunkMarkup.parse("   \n  ").ok()).isFalse();
    }

    @Test
    @DisplayName("兼容 Windows 换行（CRLF）")
    void handlesCrlf() {
        ChunkMarkup.Parsed p = ChunkMarkup.parse(
                "=== 甲 === <!-- id: a -->\r\n正文一\r\n\r\n=== 乙 === <!-- id: b -->\r\n正文二\r\n");

        assertThat(p.ok()).as(p.errors().toString()).isTrue();
        assertThat(p.blocks()).hasSize(2);
        assertThat(p.blocks().get(0).body()).as("不能把 \\r 留在正文里").isEqualTo("正文一");
        assertThat(p.blocks().get(1).body()).isEqualTo("正文二");
    }

    /* ==================== 往返 ==================== */

    @Test
    @DisplayName("★ render → parse 往返不丢东西（含标题里的 = 与正文里像分隔符的行）")
    void roundTripIsLossless() {
        List<ChunkMarkup.Block> original = List.of(
                new ChunkMarkup.Block("kiln", "kiln", "熔炉", "第一段。\n\n第二段。", 1),
                new ChunkMarkup.Block("ab", "ab", "a=b 标题", "正文里有 " + BS + "=== 冒牌标题 ===" + "\n和别的",
                        5));

        String rendered = ChunkMarkup.renderDocument(original);
        ChunkMarkup.Parsed back = ChunkMarkup.parse(rendered);

        assertThat(back.ok()).as(back.errors().toString()).isTrue();
        assertThat(back.blocks()).hasSize(2);
        assertThat(back.blocks().get(0).id()).isEqualTo("kiln");
        assertThat(back.blocks().get(0).title()).isEqualTo("熔炉");
        assertThat(back.blocks().get(0).body()).isEqualTo("第一段。\n\n第二段。");
        assertThat(back.blocks().get(1).title()).as("标题里的 = 要能原样回来").isEqualTo("a=b 标题");
        assertThat(back.blocks().get(1).key()).isEqualTo("ab");
    }

    @Test
    @DisplayName("render 会自动给「长得像分隔符」的正文行加转义")
    void renderEscapesDelimiterLikeBodyLines() {
        String rendered = ChunkMarkup.render(
                new ChunkMarkup.Block("x", "x", "标题", "=== 正文里的一行 ===", 1));

        assertThat(rendered).contains(BS + "=== 正文里的一行 ===");
        assertThat(ChunkMarkup.parse(rendered).blocks()).hasSize(1);
    }
}
