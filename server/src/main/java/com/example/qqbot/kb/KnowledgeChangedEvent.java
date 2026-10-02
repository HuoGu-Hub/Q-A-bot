package com.example.qqbot.kb;

/**
 * 知识库内容变了 —— 关心它的人自己订阅。
 *
 * <h2>为什么要有这个事件，而不是直接调公开站</h2>
 * 原先 {@code kb.term.KbTermService} 持有一个
 * {@code ObjectProvider<PublicSearchService>}，写完之后直接调它的 {@code invalidate()}。
 * 这是**层次倒置**：知识库反过来知道"公开站有个检索缓存"这件事 ——
 * 而且 {@code ObjectProvider} 那个写法本身就是为绕开循环依赖打的补丁。
 *
 * <p>现在知识库只宣布"我变了"，**谁关心谁自己订阅**：
 * 公开站在自己那边监听并清缓存。知识库不再知道公开站存在。
 *
 * <p>顺带解掉循环依赖：不再需要 {@code ObjectProvider} 这种"晚一点再拿"的技巧。
 *
 * @param reason 变更原因，只用于日志（如"词条变更"）
 */
public record KnowledgeChangedEvent(String reason) {
}
