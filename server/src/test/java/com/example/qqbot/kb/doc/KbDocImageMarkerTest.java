package com.example.qqbot.kb.doc;

import com.example.qqbot.files.ProjectFiles;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 「在知识库文档里配图」这条路的验收：**正文里的图片标记不会被解析器弄坏**。
 *
 * <p>导入链路只做两件事 —— 找分隔符、存正文。它不认识 {@code [[img:...]]}，
 * 也就不该动它。这条测试钉住这一点：以后谁给正文加清洗逻辑，必须在这里说明标记怎么办。
 */
class KbDocImageMarkerTest {

    private static final String DOC = """
            <!-- doc
            name: 酸蚀砍刀
            url: https://enshrouded.wiki.gg/wiki/Acid_Cleaver
            -->

            === 酸蚀砍刀（Acid Cleaver） === <!-- id: acid-cleaver -->
            一把涂满酸液与胆汁的砍刀。
            获取：由拾荒者狂战士掉落。
            [[img:kb:acid-cleaver]]
            """;

    @Test
    @DisplayName("★ 标记原样留在块正文里，块 id 不受影响")
    void markerSurvivesParsing() {
        ChunkMarkup.Parsed parsed = ChunkMarkup.parse(DOC);

        assertThat(parsed.blocks()).hasSize(1);
        ChunkMarkup.Block block = parsed.blocks().get(0);
        assertThat(block.id()).isEqualTo("acid-cleaver");
        assertThat(block.title()).isEqualTo("酸蚀砍刀（Acid Cleaver）");
        assertThat(block.body()).contains("[[img:kb:acid-cleaver]]");
    }

    @Test
    @DisplayName("仓库里那份带配图的样例文档必须真的合规（作者会直接抄它）")
    void shippedSampleIsValid() throws IOException {
        Path root = ProjectFiles.projectRoot();
        assertThat(root).as("测试必须在工程目录内运行，否则这个守卫是空的").isNotNull();
        Path sample = root.resolve("docs/samples/kb-doc-image.md");
        assertThat(Files.isRegularFile(sample)).as("样例文档不见了：%s", sample).isTrue();

        ChunkMarkup.Parsed parsed = ChunkMarkup.parse(Files.readString(sample, StandardCharsets.UTF_8));

        assertThat(parsed.blocks()).hasSize(1);
        assertThat(parsed.blocks().get(0).idMissing()).isFalse();
        assertThat(parsed.blocks().get(0).body()).endsWith("[[img:kb:acid-cleaver]]");
    }
}
