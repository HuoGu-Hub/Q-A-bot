package com.example.qqbot.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 把 {@link GuardProperties} 的**嵌套配置对象**分别暴露成 bean。
 *
 * <h2>解决什么问题</h2>
 * {@code GuardProperties} 是一个 **661 行**的巨型配置类，被 **17 个类**注入
 * （`GuardProperties` 是 fan-in 排行榜第一）。但逐类核对后发现：
 * 其中 **12 个只用到一个嵌套对象** —— `RateLimitStage` 只要 `RateLimit`、
 * `PathGuard` 只要 `FileAccess`、`ChatService` 只要 `ContentGate`…
 * 它们却都得把整个 661 行的对象拖进构造函数。
 *
 * <p>后果：改配置结构 = 改一片类；而且看构造签名完全看不出这个类到底关心哪几项配置。
 *
 * <h2>做法：只加 bean，不改绑定</h2>
 * `GuardProperties` 仍然是唯一的 `@ConfigurationProperties("app.guard")` 绑定者，
 * **yml 的键一个都没变**（`app.guard.rate-limit.*` 照旧）。
 * 这里只是把已经存在的嵌套对象**再暴露一次**，让业务类能只声明自己需要的那一小块。
 *
 * <p><b>引用是共享的</b>：这些 bean 就是 `GuardProperties` 里的同一个对象实例。
 * 配置中心（`SettingsService`）改值时是**就地改字段**，所以拿到 bean 的类会实时看到新值 ——
 * 这也是刻意保留的语义（见 `SettingsService` 类注释）。
 */
@Configuration
public class GuardConfig {

    @Bean
    public GuardProperties.Access guardAccess(GuardProperties props) {
        return props.getAccess();
    }

    @Bean
    public GuardProperties.RateLimit guardRateLimit(GuardProperties props) {
        return props.getRateLimit();
    }

    @Bean
    public GuardProperties.Budget guardBudget(GuardProperties props) {
        return props.getBudget();
    }

    @Bean
    public GuardProperties.Outbound guardOutbound(GuardProperties props) {
        return props.getOutbound();
    }

    @Bean
    public GuardProperties.ContentGate guardContentGate(GuardProperties props) {
        return props.getContentGate();
    }

    @Bean
    public GuardProperties.Words guardWords(GuardProperties props) {
        return props.getWords();
    }

    @Bean
    public GuardProperties.FileAccess guardFileAccess(GuardProperties props) {
        return props.getFileAccess();
    }
}
