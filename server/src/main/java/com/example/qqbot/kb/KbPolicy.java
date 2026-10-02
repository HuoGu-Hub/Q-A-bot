package com.example.qqbot.kb;

/**
 * 知识库检索（RAG）配置 —— **业务侧自己声明的只读视图**，由 {@code config.KbProperties} 实现。
 *
 * <h2>为什么业务包不直接注入 KbProperties</h2>
 * 那个类在 {@code config} 包里。业务包依赖 config 的后果：{@code config} 变成"上帝包"、
 * 构造签名看不出这个类关心哪几项、测试要为一个开关造出整个配置对象。
 * 收成「业务侧声明接口、config 侧实现它」之后，业务包对 config 的 import 就没有了，
 * 依赖方向也变成 {@code config → kb}（装配层去满足业务声明）。
 *
 * <h2>⚠️ 为什么是接口而不是 record 快照</h2>
 * 配置是**热生效**的：{@code SettingsService} 改值时是**就地改字段**，
 * 各个 Bean 长期持有引用、调用时实时读。写成 record 就是在装配时<b>拷了一份值</b> ——
 * 后台改配置再也看不到效果，而且不报错。
 *
 * <p><b>方法名保留 {@code isXxx}/{@code getXxx}</b>：这样 {@code KbProperties}
 * 一行都不用改就能实现它，也就不存在"适配器写漏一项"的可能。
 * 想反向转回可变对象必须 import {@code config} —— 那会被 ArchUnit 的
 * 「业务包不得直接依赖 config」当场抓住。
 *
 * <p>6 个分组（{@link Embedding} / {@link Rerank} / {@link MapSync} /
 * {@link WikiImport} / {@link TitleIndex} / {@link PublicSearch}）本身也**搬到了本包** ——
 * 它们是知识库自己的领域词汇，只是碰巧从 yml 绑定。所以本接口的返回类型全是 {@code kb} 包里的类。
 *
 * <p>完整理由与粒度说明见 {@code command.CommandPolicy}（债务⑥ 的模板样例）。
 */
public interface KbPolicy {

    /** 运行时检索总开关。关掉后退化成「没有知识库」的普通回答 */
    boolean isEnabled();

    /** 知识库根目录（相对路径以程序工作目录为基准） */
    String getDir();

    /** 取前几条资料拼进 prompt */
    int getTopK();

    /** 相似度下限（余弦，0~1）——低于它的资料宁可不要 */
    double getMinScore();

    /** C 路（标题向量）的闸门阈值；{@code <= 0} 表示关掉 C 路 */
    double getTitleGate();

    /** 标题向量补建 */
    TitleIndex getTitleIndex();

    /** 向量模型服务 */
    Embedding getEmbedding();

    /** 重排（cross-encoder） */
    Rerank getRerank();

    /** wiki 地图数据同步 */
    MapSync getMapSync();

    /** wiki 文章导入（任务 / 机制） */
    WikiImport getWikiImport();

    /** 公开站检索 */
    PublicSearch getPublicSearch();
}
