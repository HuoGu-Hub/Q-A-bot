package com.example.qqbot.kb;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.rag.content.Content;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 资料注入的验收 —— 核心是<b>契约保真</b>。
 *
 * <p>系统提示词第九章整章挂在 {@code <<<KNOWLEDGE>>>} 标记与"两段出处"上。
 * 换成官方管线之后，这层格式由 {@link KbContentInjector} 产出，
 * 所以必须有一条测试钉住它 —— 拼错了不会报错，只会让那一章<b>静默失效</b>。
 */
class KbContentInjectorTest {

    private final KbContentInjector injector = new KbContentInjector(new TwoSectionInjector());

    private static KbRetriever.Hit hit(String id, String title, String text, boolean curated, String at) {
        KbCorpus.Entry e = new KbCorpus.Entry(id, "doc-" + id, title, text, "https://w/" + id,
                List.of(), curated, at, null);
        return new KbRetriever.Hit(e, 0.9, "vector");
    }

    private static String textOf(ChatMessage m) {
        return ((UserMessage) m).singleText();
    }

    @Test
    @DisplayName("★ 契约保真：资料包进 <<<KNOWLEDGE>>>，且按出处分两段、维护的在前")
    void keepsKnowledgeContract() {
        List<Content> contents = List.of(
                KbContents.of(hit("imported", "酸性菌丝", "导入的正文", false, "2026-09-20")),
                KbContents.of(hit("ours", "灵火祭坛", "我们改过的正文", true, "2026-10-01")));

        String text = textOf(injector.inject(contents, UserMessage.from("灵火祭坛在哪")));

        assertThat(text).as("用户原话必须还在").startsWith("灵火祭坛在哪");
        assertThat(text).contains("<<<KNOWLEDGE").contains("KNOWLEDGE>>>");
        assertThat(text).contains("【我们维护的内容】").contains("【导入的资料");
        assertThat(text).contains("我们改过的正文").contains("导入的正文");
        assertThat(text.indexOf("【我们维护的内容】"))
                .as("我们维护的那段排在导入那段之前")
                .isLessThan(text.indexOf("【导入的资料"));
    }

    @Test
    @DisplayName("没有资料时不加标记，用户原话原样返回")
    void noContentsMeansNoMarker() {
        ChatMessage in = UserMessage.from("你好");
        assertThat(injector.inject(List.of(), in)).isSameAs(in);
        assertThat(injector.inject(null, in)).isSameAs(in);
    }

    @Test
    @DisplayName("★ 元数据往返保真：Content 还原回条目后，出处/时间/标题都不丢")
    void metadataRoundTrip() {
        KbRetriever.Hit original = hit("ours", "灵火祭坛", "正文", true, "2026-10-01");

        KbRetriever.Hit back = KbContents.toHit(KbContents.of(original));

        assertThat(back.entry().id()).isEqualTo("ours");
        assertThat(back.entry().title()).isEqualTo("灵火祭坛");
        assertThat(back.entry().text()).isEqualTo("正文");
        assertThat(back.entry().url()).isEqualTo("https://w/ours");
        assertThat(back.entry().curated()).as("出处标记必须能读回来，否则两段会拼反").isTrue();
        assertThat(back.entry().contentAt()).isEqualTo("2026-10-01");
        assertThat(back.score()).isEqualTo(0.9);
        assertThat(back.source()).isEqualTo("vector");
    }
}
