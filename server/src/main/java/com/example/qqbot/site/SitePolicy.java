package com.example.qqbot.site;

/**
 * 站点信息（公开站「关于」页展示的群信息）—— **业务侧自己声明的只读视图**，
 * 由 {@code config.SiteProperties} 实现。
 *
 * <p>这些是**热生效**的 —— 后台改完立即起作用，不用重启。
 *
 * <p>⚠️ 接口而不是 record：配置是热生效的（{@code SettingsService} 就地改字段、
 * 各 Bean 长期持引用实时读），写成快照就再也看不到后台的修改，而且不报错。
 *
 * <p><b>方法名保留 {@code getXxx}</b>：这样 {@code SiteProperties} 一行都不用改就能实现它，
 * 也就不存在"适配器写漏一项"的可能。想反向转回可变对象必须 import {@code config} ——
 * 那会被 ArchUnit 的「业务包不得直接依赖 config」当场抓住。
 *
 * <p>{@link Carousel} 本身也**搬到了本包** —— 它是公开站的领域词汇，只是碰巧从 yml 绑定。
 *
 * <p>完整理由与粒度说明见 {@code command.CommandPolicy}（债务⑥ 的模板样例）。
 */
public interface SitePolicy {

    /** 群名称 */
    String getGroupName();

    /** 群号（留空则不在公开站显示） */
    String getGroupNumber();

    /** 群说明：这个群是干什么的 */
    String getGroupDesc();

    /** 加群提示：怎么加 */
    String getJoinHint();

    /** 首页图片轮播 */
    Carousel getCarousel();
}
