package com.example.qqbot.kb;

/**
 * 知识库条目的**身份** —— 「块 id 就是词条表的 {@code en}」这条约定的类型。
 *
 * <h2>为什么需要一个类型</h2>
 * 块与名字是两个真源，靠一个字符串 joiner 起来：{@code KbBlock.id()} 的值等于
 * 词条表的 {@code en}。这条约定原先只写在注释里，于是两种错误都能编译通过：
 * <ul>
 *   <li>把 {@code docId}（文档名）当成块 id 传给 {@code store.get(...)} ——
 *       静默返回 null，界面显示"没有这块"，日志里什么都没有；</li>
 *   <li>把标题当成块 id 传进来 ——
 *       查不到、写不进，日志里什么都没有。</li>
 * </ul>
 * 类型化之后这两类错误在编译期就停住。
 *
 * <h2>边界在哪</h2>
 * 类型在**领域内**使用，字符串只出现在三层边界上，转换点因此可以一眼数清：
 * <ul>
 *   <li>持久化：{@code persistence} 的仓储收 {@code String}（SQL 参数）；</li>
 *   <li>HTTP：{@code BlockView} 这类响应对象仍然带 {@code String}（前端不关心类型）；</li>
 *   <li>模型侧：LangChain4j 的 {@code Metadata} / {@code EmbeddingMatch} 只认 {@code String}。</li>
 * </ul>
 * 转换只有两个方向：进领域 {@code EntryId.of(...)}，出领域 {@code id.value()}。
 *
 * <p>身份的**创建点**全系统只有两处：文档导入（解析出的块 id 落库时）
 * 与词条行从库里读出来时。其余地方都只是传递。
 *
 * <h2>怎么用：两个身份比较，别拿 value() 去比</h2>
 * {@code a.equals(b)} 两边都是 {@link EntryId} 才成立；
 * {@code entryId.equals("原字符串")} 与 {@code "原字符串".equals(entryId)}
 * <b>恒为 false</b>，而 {@code equals} 收的是 {@code Object}，编译器看不住。
 * 这个坑真的踩过一次：置顶逻辑里"已经有它"永远判不成立，表现是同一块出现两次。
 * 需要和字符串比时，两边都取 {@code value()}；能留类型就留类型。
 *
 * @param value 身份的原始字符串。不会为 null；空串用 {@link #isBlank()} 判
 */
public record EntryId(String value) {

    /**
     * 由原始字符串得到身份 —— **唯一**的创建入口。
     *
     * <p>顺手 trim：从 Excel、TSV、SQLite 读进来的值常带行尾空白，
     * 而带空白的身份会让 {@code get} 查不到、{@code upsert} 又插进新行。
     */
    public static EntryId of(String raw) {
        return new EntryId(raw == null ? "" : raw.trim());
    }

    /** 空身份 —— 没有值时用它，比 null 少一层判空 */
    public static EntryId empty() {
        return new EntryId("");
    }

    public boolean isBlank() {
        return value.isEmpty();
    }

    /**
     * 以原始字符串形式呈现。
     *
     * <p>日志、TSV、xlsx、异常消息都靠它保持原样 —— 类型化不该改变任何输出。
     */
    @Override
    public String toString() {
        return value;
    }
}
