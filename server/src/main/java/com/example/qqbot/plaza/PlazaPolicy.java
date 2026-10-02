package com.example.qqbot.plaza;

/**
 * 问答广场的配置 —— **业务侧自己声明的只读视图**，由 {@code config.PlazaProperties} 实现。
 *
 * <h2>为什么业务包不直接注入配置类</h2>
 * 那个类在 {@code config} 包里。业务包依赖 config 的后果：{@code config} 变成"上帝包"、
 * 构造签名看不出这个类关心哪几项、测试要为一个开关造出整个配置对象。
 * 收成「业务侧声明接口、config 侧实现它」之后，业务包对 config 的 import 就没有了，
 * 依赖方向也变成 {@code config → plaza}（装配层去满足业务声明）。
 *
 * <h2>⚠️ 为什么是接口而不是 record 快照</h2>
 * 配置是**热生效**的：{@code SettingsService} 改值时是**就地改字段**，
 * 各个 Bean 长期持有引用、调用时实时读。写成 record 就是在装配时<b>拷了一份值</b> ——
 * 后台改配置再也看不到效果，而且不报错。
 *
 * <p><b>方法名保留 {@code isXxx}/{@code getXxx}</b>：这样配置类一行都不用改就能实现它，
 * 也就不存在"适配器写漏一项"的可能。想反向转回可变对象必须 import {@code config} ——
 * 那会被 ArchUnit 的「业务包不得直接依赖 config」当场抓住。
 *
 * <p>完整理由与粒度说明见 {@code command.CommandPolicy}（债务⑥ 的模板样例）。
 */
public interface PlazaPolicy {

    /** 广场总开关 */
    boolean isEnabled();

    /** 是否只展示被点赞过的答案 */
    boolean isOnlyVoted();

    /** 被踩到几次算「全被踩」（触发下架） */
    int getDownvoteThreshold();

    /** 一个关键词最多展示几条答案 */
    int getMaxAnswersPerKeyword();

    /** 去重阈值（余弦相似度） */
    double getDedupThreshold();

    /** 同一关键词每天最多被问几次 */
    int getAskLimitPerKeywordPerDay();

    /** 广场每天最多被问几次（全局） */
    int getAskLimitGlobalPerDay();

    /** 同一个问题每天最多求助几次 */
    int getHelpLimitPerQuestionPerDay();

    /** 同一个人每天最多求助几次 */
    int getHelpLimitPerUserPerDay();

    /** 同一个群每小时最多求助几次（防刷屏） */
    int getHelpLimitPerGroupPerHour();

    /** 投票者哈希用的盐（空 = 用内置默认盐） */
    String getVoteSalt();
}
