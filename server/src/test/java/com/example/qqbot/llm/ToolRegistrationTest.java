package com.example.qqbot.llm;

import com.example.qqbot.kb.KbCorpus;
import com.example.qqbot.kb.KbLookupTool;
import com.example.qqbot.kb.KbRetriever;
import com.example.qqbot.kb.MapOptions;
import com.example.qqbot.kb.PlaceDirectory;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.agent.tool.ToolSpecifications;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * 工具名必须**三处一致**：注册给模型的名字、授权白名单、system.md 里写的名字。
 *
 * <p>2026-10-15 查出的隐患：方法叫 {@code searchTitles}，而白名单与 system.md 都写
 * {@code search_titles} —— 模型照 system.md 去调，永远调不到真工具（它只能自己"编"，
 * 编出来的标记还会漏到群里）。这条测试把三者钉在一起。
 */
class ToolRegistrationTest {

    private static List<ToolSpecification> specs() {
        KbLookupTool tool = new KbLookupTool(mock(KbRetriever.class), mock(KbCorpus.class),
                mock(PlaceDirectory.class), new MapOptions());
        return ToolSpecifications.toolSpecificationsFrom(tool);
    }

    @Test
    @DisplayName("★ 注册的工具名必须在授权白名单里（默认拒绝的另一半：能调到的都在白名单）")
    void everyRegisteredToolIsWhitelisted() {
        List<String> names = specs().stream().map(ToolSpecification::name).toList();

        assertThat(names).as("工具一个都不能少").containsExactlyInAnyOrder("lookup", "locate", "search_titles");
        for (String name : names) {
            assertThat(KbToolAssistant.isAllowed(name))
                    .as("工具 %s 没进白名单 —— 模型一调就会被拒，而且 system.md 里还写着它", name)
                    .isTrue();
        }
    }

    @Test
    @DisplayName("★ system.md 提到的名字就是注册名：search_titles 下划线，不是 searchTitles")
    void searchTitlesUsesSnakeCaseName() {
        assertThat(specs().stream().map(ToolSpecification::name))
                .contains("search_titles")
                .doesNotContain("searchTitles");
    }
}
