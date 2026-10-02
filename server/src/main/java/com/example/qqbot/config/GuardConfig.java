package com.example.qqbot.config;

import com.example.qqbot.guard.Access;
import com.example.qqbot.guard.Budget;
import com.example.qqbot.guard.ContentGate;
import com.example.qqbot.guard.FileAccess;
import com.example.qqbot.guard.Outbound;
import com.example.qqbot.guard.RateLimit;
import com.example.qqbot.guard.Words;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 把 {@link GuardProperties} 持有的**各个分组对象**分别暴露成 bean。
 *
 * <h2>解决什么问题（2026-10-02 收尾）</h2>
 * 这里原本是债务⑥ 的第一步：`GuardProperties` 那时是个 **661 行**的巨型配置类、
 * 被 **17 个类**注入（fan-in 第一），而其中 12 个只用到一个嵌套对象 ——
 * 于是把嵌套对象单独暴露成 bean，让它们不必拖整个对象进来。**fan-in 从 17 降到 3。**
 *
 * <p>但那只是第一步：那些 bean 的**类型**仍然是 `config.GuardProperties.RateLimit`，
 * 所以护栏（业务包不得依赖 config）照样不通过。**第二步是把 7 个分组整体搬去
 * `guard` 包** —— 它们是安全中间层的领域词汇，只是碰巧从 yml 绑定。
 * 现在 `GuardProperties` 只剩 129 行（就是那 7 个字段 + 2 个根开关 + getter/setter），
 * 而本类把 `guard` 包里的它们暴露成 bean。
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
    public Access guardAccess(GuardProperties props) {
        return props.getAccess();
    }

    @Bean
    public RateLimit guardRateLimit(GuardProperties props) {
        return props.getRateLimit();
    }

    @Bean
    public Budget guardBudget(GuardProperties props) {
        return props.getBudget();
    }

    @Bean
    public Outbound guardOutbound(GuardProperties props) {
        return props.getOutbound();
    }

    @Bean
    public ContentGate guardContentGate(GuardProperties props) {
        return props.getContentGate();
    }

    @Bean
    public Words guardWords(GuardProperties props) {
        return props.getWords();
    }

    @Bean
    public FileAccess guardFileAccess(GuardProperties props) {
        return props.getFileAccess();
    }
}
