package com.example.qqbot;

import com.example.qqbot.kb.KbRetriever;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * **整个 Spring 上下文能不能起来** —— 这条测试存在的唯一理由，是防止
 * "服务启动不了"这类问题再一次无声地潜伏好几天。
 *
 * <h2>真实故障（2026-10-02 引入，10-04 才发现）</h2>
 * {@code KbRetriever} 有主构造 + 一个给测试用的便利构造，两个都没标 {@code @Autowired}。
 * Spring 的规则是"多构造器且都无标注 → 退回找无参构造器"，于是
 * {@code No default constructor found}，<b>整个应用起不来</b>。
 *
 * <p>而它<b>骗过了当时全部 368 个测试和 12 条 ArchUnit 规则</b>：
 * 单元测试都是自己 {@code new} 的，根本不走 Spring 装配。
 * 直到有人真的去启动服务，才发现它已经起不来好几天了。
 *
 * <h2>⚠️ 为什么路径必须算出来、而且必须放系统临时目录</h2>
 * 这套数据路径不能留在工作目录下，有两个坑叠在一起：
 * <ol>
 *   <li>留在工作目录下就会打开**生产库**（{@code server/data/qa/qqbot.sqlite}）；</li>
 *   <li>而 {@code app.guard.file-access.blocked-path-parts} 里**有 {@code .toolchain/}**
 *       —— 隔离构建目录 {@code .toolchain/server-ci} 下的任何路径都会被 PathGuard 拒绝，
 *       于是 {@code MediaStorageGuard} 直接拒绝启动。</li>
 * </ol>
 * 所以这里把数据落到 {@code java.io.tmpdir} 下的独立子目录，并把它加进
 * {@code allowed-roots}。这样无论从哪个工作目录跑都一致 —— 测试不该依赖工作目录。
 *
 * <p>⚠️ 这里验证的是**装配**，不是业务。它跑 2 秒、不连外网、只写临时目录。
 */
@SpringBootTest
class ApplicationStartsTest {

    /** 本次运行专属的临时目录（同一个测试类的多个用例共用同一个上下文） */
    private static final Path BASE = createBase();

    private static Path createBase() {
        try {
            return Files.createTempDirectory("qqbot-bootcheck-");
        } catch (IOException e) {
            throw new IllegalStateException("建不了临时目录", e);
        }
    }

    @DynamicPropertySource
    static void dataPaths(DynamicPropertyRegistry r) {
        r.add("app.persistence.db", () -> BASE.resolve("boot.sqlite").toString());
        r.add("app.kb.dir", () -> BASE.resolve("kb").toString());
        r.add("app.media.temp-images.dir", () -> BASE.resolve("tmp-images").toString());
        r.add("app.media.kb-images.dir", () -> BASE.resolve("kb-images").toString());
        r.add("app.site.carousel.dir", () -> BASE.resolve("site-images").toString());
        // allowed-roots 留空时只允许"程序工作目录"，而临时目录在工作目录之外 —— 必须显式放行
        r.add("app.guard.file-access.allowed-roots[0]", () -> BASE.toString());
        // 不起 Web 服务器：这是装配验证，不需要占端口（生产服务可能正占着 8080）
        r.add("spring.main.web-application-type", () -> "none");
    }

    @Autowired
    private ApplicationContext ctx;

    @Test
    @DisplayName("★ 上下文能装配起来 —— 能跑到这里就说明没有「启动即炸」的装配问题")
    void contextLoads() {
        assertThat(ctx).isNotNull();
        assertThat(ctx.getBeanDefinitionCount()).isGreaterThan(100);
    }

    @Test
    @DisplayName("★ 那个曾经起不来的 Bean 确实在容器里（KbRetriever，两个构造器）")
    void theBeanThatOnceBrokeIsWired() {
        assertThat(ctx.getBean(KbRetriever.class)).isNotNull();
    }

    @Test
    @DisplayName("★ 重构时抽成接口的那批依赖，都真的有实现被装配进去了")
    void policyInterfacesAreSatisfied() {
        // 这些都是 2026-10-02 债务⑥ 去中心化时从 config.XxxProperties 抽出来的只读接口。
        // 抽错一个（忘了 implements、或忘了 @EnableConfigurationProperties）
        // 就是"启动即炸"，而单元测试照样全绿。
        for (Class<?> iface : new Class<?>[]{
                com.example.qqbot.kb.KbPolicy.class,
                com.example.qqbot.media.MediaPolicy.class,
                com.example.qqbot.guard.GuardSwitch.class,
                com.example.qqbot.llm.LlmPolicy.class,
                com.example.qqbot.qa.QaPolicy.class,
                com.example.qqbot.site.SitePolicy.class,
                com.example.qqbot.site.BotPolicy.class,
                com.example.qqbot.command.CommandPolicy.class,
                com.example.qqbot.router.AsyncPolicy.class,
                com.example.qqbot.persistence.PersistencePolicy.class,
        }) {
            assertThat(ctx.getBeanNamesForType(iface))
                    .as("接口 %s 没有任何实现被装配 —— 抽接口时漏了 implements 或漏注册", iface.getName())
                    .isNotEmpty();
        }
    }
}
