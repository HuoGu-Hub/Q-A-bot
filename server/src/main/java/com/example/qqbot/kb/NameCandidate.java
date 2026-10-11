package com.example.qqbot.kb;

/**
 * 一个**待登记的名字** —— 块侧产出，名字层决定是否落库。
 *
 * <h2>为什么它住在核心包</h2>
 * 生产者是 {@code kb.block.KbDocImporter}（导入文档时它才知道每个块的 id 与标题），
 * 消费者是 {@code kb.term}（名字表的写入口）。这两层<b>不允许互相认识</b> ——
 * 那正是要消掉的那条边。所以契约由核心承载：
 * 两边各自依赖核心，谁也不必 import 对方。
 *
 * <p>它是一份<b>建议</b>，不是命令：是否落库、要不要覆盖，由名字层按自己的策略决定
 * （见 {@code KbTermService.registerMissing}）。
 *
 * @param en   词条身份。**等于块 id** —— "一个块 = 一个词条"这条约定就落在这个字段上
 * @param name 建议的中文名，由 {@link TermNames#termName} 从块标题推出
 */
public record NameCandidate(com.example.qqbot.kb.EntryId en, String name) {
}
