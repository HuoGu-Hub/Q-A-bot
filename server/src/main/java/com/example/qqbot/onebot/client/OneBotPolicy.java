package com.example.qqbot.onebot.client;

/**
 * OneBot 协议端的配置 —— **业务侧自己声明的只读视图**，由 {@code config.OneBotProperties} 实现。
 *
 * <h2>为什么业务包不直接注入配置类</h2>
 * 那个类在 {@code config} 包里。业务包依赖 config 的后果：{@code config} 变成"上帝包"、
 * 构造签名看不出这个类关心哪几项、测试要为一个开关造出整个配置对象。
 * 收成「业务侧声明接口、config 侧实现它」之后，业务包对 config 的 import 就没有了，
 * 依赖方向也变成 {@code config → onebot.client}（装配层去满足业务声明）。
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
public interface OneBotPolicy {

    /** HTTP API 地址 */
    String getApiBase();

    /** 访问令牌（空 = 不鉴权） */
    String getAccessToken();

    /** 单次请求超时（毫秒） */
    int getRequestTimeoutMs();
}
