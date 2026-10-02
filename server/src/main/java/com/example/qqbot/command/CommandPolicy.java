package com.example.qqbot.command;

/**
 * 指令系统的配置 —— **业务侧自己声明的只读视图**，由 {@code config.CommandProperties} 实现。
 *
 * <h2>为什么业务包不直接注入 CommandProperties</h2>
 * 那个类在 {@code config} 包里。业务包依赖 config 的后果有三个，而且都不出声：
 * <ul>
 *   <li><b>{@code config} 变成"上帝包"</b> —— 谁都依赖它，它谁都动不了；
 *       改一个配置类的位置，编译错误会撒在一片业务类里。</li>
 *   <li><b>构造签名看不出这个类关心什么</b> —— {@code CommandProperties} 有 6 项，
 *       而 {@code CommandRateLimiter} 只用其中 1 项。</li>
 *   <li><b>测试要为一个开关造出整个配置对象</b>。</li>
 * </ul>
 * 收成「业务侧声明接口、config 侧实现它」之后，业务包对 config 的 import 就没有了。
 * <b>依赖方向也变对了</b>：{@code config → command}（装配层去满足业务声明），
 * 而不是 {@code command → config}。
 *
 * <h2>⚠️ 为什么是接口而不是 record 快照</h2>
 * 配置是**热生效**的：{@code SettingsService} 改值时是**就地改字段**，
 * 各个 Bean 长期持有引用、调用时实时读。
 * 如果这里写成 {@code record CommandPolicy(boolean enabled, ...)}，那是在装配时
 * <b>拷了一份值</b> —— 后台改配置再也看不到效果，而且不报错。
 * 「接口 + 由那个可变对象实现」天然就是活的：
 * {@code CommandMatcherTest} 里就有三处「构造完 matcher 之后再改 props」的用例，
 * 换快照会当场挂掉。
 *
 * <h2>粒度：一个配置类一个接口</h2>
 * 不做更细的拆分 —— 这 6 项是"指令系统怎么工作"这一件事；
 * 拆成 3 个接口只会让 {@code CommandController}（后台要展示全部）拿 3 个参数。
 * 将来某个消费者只用一两项、而字段继续膨胀时再拆：那时是加一个接口 + 改一个构造器。
 *
 * <p><b>方法名保留 {@code isXxx}/{@code getXxx}</b>：这样 {@code CommandProperties}
 * 一行都不用改就能实现它，也就不存在"适配器写漏一项"的可能。
 * 想反向转回可变对象必须 import {@code config} —— 那会被 ArchUnit 的
 * 「业务包不得直接依赖 config」当场抓住。
 */
public interface CommandPolicy {

    /** 指令系统总开关 */
    boolean isEnabled();

    /** 群里触发指令是否必须 @ 机器人 */
    boolean isRequireMention();

    /** 是否需要 "/" 前缀 */
    boolean isRequireSlash();

    /** 指令的独立限流（每人每分钟几次） */
    int getRateLimitPerMinute();

    /** 是否允许 {user.id} / {group.id} 这类高级变量 */
    boolean isAllowUserIds();

    /** 回复最大长度（防止配了一个超长文本把群刷屏） */
    int getMaxReplyLength();
}
