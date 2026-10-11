package com.example.qqbot.kb;

import java.util.List;

/**
 * 注入器 —— 把命中的块渲染成给模型看的资料文本。
 *
 * <p>它只管<b>资料那一段</b>，外面那层 {@code KNOWLEDGE} 标记由
 * {@link KbContentInjector} 在管线末端拼 —— 那是对话格式，不是知识库的事。
 *
 * <p><b>为什么这件事不该散在 agent 里</b>：怎么渲染取决于块自己的属性
 * （出处是人工还是导入、内容时间是什么），而这些属性只有 kb 认识。
 * 换一种渲染方式时，也不该去动对话编排。
 */
public interface KbKnowledgeInjector {

    /** 渲染成资料文本；没有命中时返回空串 */
    String render(List<KbRetriever.Hit> hits);
}
