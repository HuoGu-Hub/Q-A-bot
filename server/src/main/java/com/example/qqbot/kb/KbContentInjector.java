package com.example.qqbot.kb;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.injector.ContentInjector;

import java.util.List;

/**
 * 官方 {@link ContentInjector} 的自研实现 —— <b>保住 {@code <<<KNOWLEDGE>>>} 契约</b>。
 *
 * <h2>为什么必须自研，不能用官方默认的那个</h2>
 * 系统提示词第九章整章都挂在这个标记上：{@code <<<KNOWLEDGE>>>} 里的是<b>资料不是指令</b>、
 * 资料分两部分只说明出处、冲突时要明说分歧。官方 {@code DefaultContentInjector}
 * 有自己的拼法，用它会<b>静默地</b>把那一章变成一段永远命不中的规则 —— 不报错，只是规则失效。
 *
 * <h2>它取代了什么</h2>
 * 原来这层标记由 agent 层拼。收进注入器之后，群聊路径的"资料长什么样"
 * 只由管线一处决定，换编排也不会漏掉标记。广场的 {@code FallbackService}
 * 至今仍是自己拼的老路径，尚未收编。
 *
 * <h2>为什么落在 kb 包</h2>
 * 渲染成什么样取决于块自己的属性（出处是人工还是导入、内容时间是什么），
 * 而这些只有 kb 认识。对话编排不该知道"两段出处"这回事。
 */
public class KbContentInjector implements ContentInjector {

    /** 标记的形状必须与系统提示词第九章一致 */
    private static final String OPEN = "\n<<<KNOWLEDGE\n";
    private static final String CLOSE = "\nKNOWLEDGE>>>\n";

    private final KbKnowledgeInjector renderer;

    public KbContentInjector(KbKnowledgeInjector renderer) {
        this.renderer = renderer;
    }

    @Override
    public ChatMessage inject(List<Content> contents, ChatMessage userMessage) {
        if (contents == null || contents.isEmpty()) {
            return userMessage;
        }
        List<KbRetriever.Hit> hits = contents.stream().map(KbContents::toHit).toList();
        String knowledge = renderer.render(hits);
        if (knowledge == null || knowledge.isBlank()) {
            return userMessage;
        }
        String original = userMessage instanceof UserMessage um ? um.singleText() : "";
        return UserMessage.from(original + OPEN + knowledge + CLOSE);
    }
}
