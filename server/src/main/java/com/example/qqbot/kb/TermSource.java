package com.example.qqbot.kb;

import java.util.List;

/**
 * 词条名字的**只读视图** —— B 路关键词检索只需要这么多。
 *
 * <h2>改造后的模型：词条就是分块</h2>
 * 一个词条 = 一个分块，字段是 {@code id + 标题 + 别名}。原来的 {@code kb_term}
 * 名字表已经删掉（核对状态一并删掉），所以这份名字数据的实现方就是块表
 * （{@code kb.block.KbBlockStore}）。
 *
 * <p>接口仍然留在核心包：检索核心只依赖"能拿到一批名字"这件事，
 * 不认识块是怎么存、怎么写的。实现方反过来依赖核心，方向单一。
 *
 * <p>契约由 {@code ArchitectureTest.kbCoreDoesNotDependOnUpperLayers} 守住。
 */
public interface TermSource {

    /**
     * 一条词条名字。
     *
     * @param id      身份（块 id）
     * @param title   标题
     * @param aliases 别名（存库那份；空串表示没单独写过别名，调用方可用标题派生兜底）
     */
    record Row(EntryId id, String title, String aliases) {
    }

    /** 词条数据可用吗。库没就绪时为 false —— B 路退化成只认英文词，但检索照常 */
    boolean isAvailable();

    /**
     * 版本号 —— 变一次代表语料变了一次。
     *
     * <p>{@link Glossary} 每次取用前比对，所以写入方不需要记得通知任何人。
     */
    long version();

    /** 全部词条名字行 */
    List<Row> rows();
}
