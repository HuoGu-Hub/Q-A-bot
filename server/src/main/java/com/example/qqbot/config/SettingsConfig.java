package com.example.qqbot.config;

import com.example.qqbot.settings.SettingsRoots;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Map;

/**
 * 把各个配置对象组装成 {@code settings} 需要的「前缀 → 对象」表。
 *
 * <h2>为什么这张表在这里（装配层）</h2>
 * 配置中心要能**就地改**这些 {@code @ConfigurationProperties} 对象的字段
 * （热生效的原理：各个 Bean 长期持有引用、调用时实时读，改掉字段下一次调用就生效）。
 * 所以它必须拿到这些对象本身 —— 但那只意味着它需要<b>若干个可变对象</b>，
 * 不意味着它需要认识这些<b>类型</b>：它改值走的是纯反射。
 *
 * <p>于是"谁来认识这些配置类"这件事收在装配层这一处。
 *
 * <h2>⚠️ 加配置类时要记得回来</h2>
 * 用一张表而不是 switch：白名单（{@code SettingsWhitelist}）里出现的**每一个**
 * 顶层前缀都必须在这张表里有归属。漏一个的表现是
 * 「那一组配置项全部改不动，每条都报『不支持的配置前缀：X』」——
 * 2026-10-01 的 llm（回复风格）就是这么漏的：白名单加了 5 项，表里没跟上，
 * 界面上怎么点都失败，而错误信息只说前缀不支持，看不出是"漏注册"。
 *
 * <p>{@code SettingsPrefixTest} 会**调用本方法**遍历白名单断言每个前缀都有人接管 ——
 * 所以表和测试是钉在一起的，漏注册会红。
 */
@Configuration
public class SettingsConfig {

    @Bean
    public SettingsRoots settingsRoots(GuardProperties guard, KbProperties kb,
                                       MediaProperties media, QaProperties qa,
                                       LogProperties logs, CommandProperties commands,
                                       SiteProperties site, LlmProperties llm,
                                       BotProperties bot) {
        return new SettingsRoots(Map.of(
                "guard", guard,
                "kb", kb,
                "media", media,
                "qa", qa,
                "logs", logs,
                "commands", commands,
                "site", site,
                "llm", llm,
                "bot", bot));
    }
}
