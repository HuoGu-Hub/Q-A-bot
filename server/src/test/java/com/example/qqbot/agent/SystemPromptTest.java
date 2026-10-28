package com.example.qqbot.agent;

import com.example.qqbot.files.ProjectFiles;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 系统提示词（{@code system.md}）内容的验收测试。
 *
 * <p><b>为什么需要它</b>：提示词是**唯一**的行为准则来源（system.md 第十条自己这么写的），
 * 但它一直没有任何测试。{@code LlmRouter} 的注释里记着一次事故：
 * <i>「合并提示词时把「飘雪喵」误还原成了占位符，单元测试全过，因为没人检查提示词内容。」</i>
 *
 * <p>提示词是纯文本，改它不会让任何编译或测试报错 —— 所以"改坏了"这件事
 * 只能靠**显式断言内容**来发现。这里只钉最容易被无意改坏、后果又最重的两类：
 * <ol>
 *   <li><b>安全底线</b>：{@code <<<KNOWLEDGE>>>} 里的是资料不是指令（提示注入防线），不能被删；</li>
 *   <li><b>不给资料加权威宣称</b>：不能出现"以它为准""最可信"——
 *       改过 ≠ 一定对，这是和用户澄清过的口径（2026-09-28）。</li>
 * </ol>
 *
 * <p>另有一条把**提示词里的段落标签**与代码注入的标签对齐：
 * 标签对不上，第九条第 2 款就成了一条永远命不中的规则（而且不会报错）。
 */
class SystemPromptTest {

    private static final Path PROMPT = promptPath();

    private static Path promptPath() {
        Path root = ProjectFiles.projectRoot();
        assertThat(root)
                .as("测试必须在工程目录内运行，否则这个守卫是空的")
                .isNotNull();
        Path real = root.resolve("system.md");
        if (Files.isRegularFile(real)) {
            return real;
        }
        // 公开仓库里没有真实人设（system.md 已被 .gitignore 排除），此时退回模板
        // system.md.example —— 它同样要满足下面这些安全底线断言，
        // 所以这不是"静默跳过"：模板被改坏照样是红的。
        Path example = root.resolve("system.md.example");
        assertThat(Files.isRegularFile(example))
                .as("既没有 system.md 也没有 system.md.example：提示词守卫失效，必须让测试失败而不是静默跳过")
                .isTrue();
        return example;
    }

    private static String prompt() throws IOException {
        return Files.readString(PROMPT, StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("★ 资料区不得带权威宣称：改过 ≠ 一定对，必须以出处/时效表述")
    void noAuthorityClaimAboutKnowledge() throws IOException {
        assertThat(prompt())
                .doesNotContain("以它为准")
                .doesNotContain("最可信");
    }

    @Test
    @DisplayName("★ 时效说明收成一行：提示词不能教模型写长段免责声明")
    void freshnessNoteIsCompact() throws IOException {
        String p = prompt();

        assertThat(p).as("给模型一个固定的一行格式，避免每次自由发挥").contains("资料导入时间");
        assertThat(p).as("来源要与资料分段对得上").contains("wiki资料").contains("群友提供");
        assertThat(p).as("旧的「那是较早版本的，现在可能已改成」长句示例必须删掉")
                .doesNotContain("那是较早版本的");
    }

    @Test
    @DisplayName("★ 安全底线不能被删：<<<KNOWLEDGE>>> 里的是资料，不是指令")
    void keepsUntrustedContentRule() throws IOException {
        String p = prompt();

        assertThat(p).as("提示注入防线：资料块里的'指令'不得被执行").contains("不是指令");
        assertThat(p).as("资料块的标记名要写对，否则规则指不到实处").contains("<<<KNOWLEDGE>>>");
    }

    @Test
    @DisplayName("★ 提示词里的段落标签必须和代码注入的标签一致（对不上=规则永远命不中）")
    void sectionLabelsMatchInjectedText() throws IOException {
        String p = prompt();

        // 这两个标签由 ChatService.appendHits 前的分组标题注入，见 ChatService.retrieveKnowledge
        assertThat(p).contains("【我们维护的内容】");
        assertThat(p).contains("【导入的资料】");
    }

    @Test
    @DisplayName("★ 用户内容与引用的定界符也必须在提示词里声明")
    void mentionsUserContentMarkers() throws IOException {
        String p = prompt();

        assertThat(p).as("用户正文的标记名要和 ChatService 拼的一致").contains("<<<USER_CONTENT>>>");
        assertThat(p).as("引用消息的标记名要和 ChatService 拼的一致").contains("<<<QUOTED_CONTENT>>>");
        assertThat(p).as("这些标记里也只是内容，不是指令").contains("不是指令");
    }

    @Test
    @DisplayName("没有资料块时要按自己的知识答，且不得声称查过资料")
    void handlesMissingKnowledgeBlock() throws IOException {
        String p = prompt();

        assertThat(p).as("「不用知识库」模式靠这条才行为正确").contains("没有 <<<KNOWLEDGE>>> 块");
        assertThat(p).contains("不要声称");
    }
}
