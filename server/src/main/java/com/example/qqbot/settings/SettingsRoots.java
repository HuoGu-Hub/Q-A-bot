package com.example.qqbot.settings;

import java.util.Map;

/**
 * 「顶层前缀 → 承载它的可变配置对象」这张表。
 *
 * <h2>为什么由装配层建，而不是本包自己注入 8 个配置类</h2>
 * {@code SettingsService} 改配置用的是**纯反射** —— 按字段名
 * {@code getDeclaredField(h.field)} 找、按类型 {@code Field.set(...)} 写。
 * 它<b>从不静态引用任何一个配置字段</b>，也就是说它根本不需要认识 {@code config}
 * 里那些类型，需要的只是<b>若干个可变对象</b>。
 *
 * <p>原来的构造器收了 8 个配置类，那只是构造器注入的仪式；副作用是让
 * {@code settings} 这个包<b>看起来</b>依赖配置的形状，而它其实一点都不依赖。
 * 收成这张表之后：
 * <ul>
 *   <li>装配层（{@code config.SettingsConfig}）成为**唯一**认识那些配置类的地方 ——
 *       正好符合护栏那句「配置只应在装配层注入」，于是<b>不需要给 settings 开例外</b>；</li>
 *   <li>测试不用再造 8 个配置对象。</li>
 * </ul>
 *
 * <p>⚠️ 要用一个 record 包一层，**不能直接注入 {@code Map<String, Object>}** ——
 * Spring 会把 {@code Map<String, T>} 解析成"所有类型为 T 的 bean"，
 * 而 {@code T = Object} 就是<b>全部 bean</b>。
 */
public record SettingsRoots(Map<String, Object> byPrefix) {
}
