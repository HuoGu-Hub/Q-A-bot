package com.example.qqbot.kb;

import java.util.List;

/**
 * 词条表 —— <b>检索侧只需要这么多</b>。
 *
 * <h2>为什么要有这个接口</h2>
 * {@link Glossary}（B 路关键词检索的燃料，每次回答都要用）原先直接依赖
 * {@code kb.term.KbTermStore}。那是「核心依赖管理侧」的倒置：词条表的写入口
 * （导入、核对、按语料 reconcile）全在 {@code kb.term}，而检索只需要读三样东西 ——
 * 可用性、版本号、行。
 *
 * <p>依赖方向因此反过来：<b>本接口由消费方声明在 {@code kb}</b>，
 * {@code kb.term.KbTermStore} 实现它。于是 {@code kb} 包不再认识 {@code kb.term}，
 * 检索核心可以脱离词条层的写路径单独编译与测试。
 *
 * <h2>为什么行类型是 Row，不是直接复用 KbTermStore.Entry</h2>
 * 用 {@code Entry} 就等于把 {@code kb.term} 的类型拖回来，依赖方向原地复原。
 * 所以这里只声明消费方真正用到的那三个字段。
 *
 * <p>契约由 {@code ArchitectureTest.kbCoreDoesNotDependOnTerm} 守住。
 */
public interface TermSource {

    /** 一条词条：英文名（= 页面标题）、中文名、核对状态 */
    record Row(EntryId en, String zh, String status) {
    }

    /** 词条表可用吗。数据库没就绪时为 false —— B 路退化成只认英文词，但检索照常 */
    boolean isAvailable();

    /**
     * 版本号 —— 变一次代表表变了一次。
     *
     * <p>调用方靠它决定要不要重载：{@link Glossary} 每次取用前比对，
     * 所以写入方（导入、核对、后台改名）不需要记得通知任何人。
     */
    long version();

    /** 全部词条行。状态过滤由调用方决定，数据源不替它做主 */
    List<Row> rows();
}
