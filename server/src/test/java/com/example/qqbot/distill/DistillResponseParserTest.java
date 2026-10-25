package com.example.qqbot.distill;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class DistillResponseParserTest {

    private final DistillResponseParser parser = new DistillResponseParser(new com.fasterxml.jackson.databind.ObjectMapper());

    @Test
    @DisplayName("解析 JSON，并过滤来源、图片与分类")
    void parsesAndFilters() {
        String body = "先找灵火祭坛，再把火把放上去。需要 3 个树脂和 2 个石头，晚上去更容易看清。";
        String reply = """
                {"notes":[
                  {"title":"灵火祭坛位置","aliases":["祭坛"],"category":"地点",
                   "body":"%s","confidence":0.82,
                   "sourceMessageIds":[1,2,99],"imageIds":[3,88]},
                  {"title":"短","body":"太短","sourceMessageIds":[1]},
                  {"title":"带链接的笔记","body":"%s http://evil.example","sourceMessageIds":[1]}
                ]}
                """.formatted(body, body);

        var drafts = parser.parse(reply, Set.of(1L, 2L), Set.of(3L),
                Set.of("地点", "大佬攻略"), 10);

        assertThat(drafts).hasSize(1);
        var d = drafts.get(0);
        assertThat(d.title()).isEqualTo("灵火祭坛位置");
        assertThat(d.category()).isEqualTo("地点");
        assertThat(d.aliases()).isEqualTo("祭坛");
        assertThat(d.sourceMessageIds()).containsExactly(1L, 2L);
        assertThat(d.imageIds()).containsExactly(3L);
    }

    @Test
    @DisplayName("未知分类回退到「大佬攻略」，空 notes 返回空列表")
    void categoryFallbackAndEmpty() {
        String body = "这是一条足够长的经验正文，用来验证分类回退与空列表行为，至少三十个字符。";
        String json = """
                {"notes":[{"title":"分类回退测试","category":"不存在的分类","body":"%s",
                "confidence":0.9,"sourceMessageIds":[1]}]}
                """.formatted(body);

        var drafts = parser.parse(json, Set.of(1L), Set.of(), Set.of("地点"), 10);
        assertThat(drafts).hasSize(1);
        assertThat(drafts.get(0).category()).isEqualTo("大佬攻略");

        assertThat(parser.parse("{\"notes\":[]}", Set.of(1L), Set.of(), Set.of(), 10)).isEmpty();
    }

    @Test
    @DisplayName("没有 JSON 或 notes 不是数组时明确报错，便于 worker 重试")
    void rejectsBadShape() {
        try {
            parser.parse("模型今天不想输出 JSON", Set.of(1L), Set.of(), Set.of(), 10);
        } catch (IllegalArgumentException e) {
            assertThat(e.getMessage()).contains("没有 JSON");
        }
        try {
            parser.parse("{\"notes\":\"x\"}", Set.of(1L), Set.of(), Set.of(), 10);
        } catch (IllegalArgumentException e) {
            assertThat(e.getMessage()).contains("notes 数组");
        }
    }
}
