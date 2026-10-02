package com.example.qqbot.media;

import com.example.qqbot.config.GuardProperties;
import com.example.qqbot.config.MediaProperties;
import com.example.qqbot.guard.PathGuard;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 目录隔离守卫的验收测试，对应验收标准③：
 * 「把 tmp-images 的路径配成 kb-images → 启动时拒绝启动并报错」。
 */
class MediaStorageGuardTest {

    @TempDir
    static Path base;

    @Test
    @DisplayName("验收③：临时目录 == 知识库目录 → 直接拒绝启动")
    void refusesWhenTmpEqualsKb() {
        MediaProperties props = propsWith(base.resolve("same"), base.resolve("same"));
        MediaStorageGuard guard = new MediaStorageGuard(props, allowAllUnderBase());

        assertThatThrownBy(guard::init)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("同一个路径");
    }

    @Test
    @DisplayName("知识库目录配到临时目录里面 → 拒绝启动")
    void refusesWhenKbInsideTmp() {
        Path tmp = base.resolve("outer");
        MediaProperties props = propsWith(tmp, tmp.resolve("kb-images"));
        MediaStorageGuard guard = new MediaStorageGuard(props, allowAllUnderBase());

        assertThatThrownBy(guard::init)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("位于临时图片目录内部");
    }

    @Test
    @DisplayName("保留时长 / 单轮上限必须为正数")
    void refusesNonPositiveNumbers() {
        MediaProperties props = propsWith(base.resolve("t2"), base.resolve("k2"));
        props.getTempImages().setRetentionHours(0);

        assertThatThrownBy(() -> new MediaStorageGuard(props, allowAllUnderBase()).init())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("retention-hours");
    }

    @Test
    @DisplayName("临时目录不在 PathGuard 允许范围内 → 拒绝启动（避免清理静默失效）")
    void refusesWhenPathGuardDeniesTmp() {
        MediaProperties props = propsWith(base.resolve("t3"), base.resolve("k3"));
        GuardProperties guardProps = new GuardProperties();
        guardProps.getFileAccess().setAllowedRoots(List.of(base.resolve("somewhere-else").toString()));

        assertThatThrownBy(() -> new MediaStorageGuard(props, new PathGuard(guardProps)).init())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("PathGuard");
    }

    @Test
    @DisplayName("配置正确时会建好两个目录并暴露绝对路径")
    void createsDirectoriesOnSuccess() {
        Path tmp = base.resolve("ok/tmp-images");
        Path kb = base.resolve("ok/kb-images");
        MediaProperties props = propsWith(tmp, kb);

        MediaStorageGuard guard = new MediaStorageGuard(props, allowAllUnderBase());
        guard.init();

        assertThat(Files.isDirectory(tmp)).isTrue();
        assertThat(Files.isDirectory(kb)).isTrue();
        assertThat(guard.tmpDir()).isEqualTo(tmp.toAbsolutePath().normalize());
        assertThat(guard.kbDir()).isEqualTo(kb.toAbsolutePath().normalize());
    }

    @Test
    @DisplayName("验收③（真实验证）：配错时 Spring 上下文启动失败")
    void springContextFailsToStartWhenMisconfigured() {
        String same = base.resolve("ctx/same").toString();
        new ApplicationContextRunner()
                .withUserConfiguration(GuardContextConfig.class)
                .withPropertyValues(
                        "app.media.temp-images.dir=" + same,
                        "app.media.kb-images.dir=" + same)
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasRootCauseInstanceOf(IllegalStateException.class);
                });
    }

    @Test
    @DisplayName("验收③（真实验证）：配置正确时 Spring 上下文正常启动")
    void springContextStartsWhenConfigured() {
        new ApplicationContextRunner()
                .withUserConfiguration(GuardContextConfig.class)
                .withPropertyValues(
                        "app.media.temp-images.dir=" + base.resolve("ctx/ok-tmp"),
                        "app.media.kb-images.dir=" + base.resolve("ctx/ok-kb"))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(MediaStorageGuard.class);
                });
    }

    // ==================== 测试脚手架 ====================

    private static MediaProperties propsWith(Path tmp, Path kb) {
        MediaProperties props = new MediaProperties();
        props.getTempImages().setDir(tmp.toString());
        props.getKbImages().setDir(kb.toString());
        return props;
    }

    private static PathGuard allowAllUnderBase() {
        GuardProperties guardProps = new GuardProperties();
        guardProps.getFileAccess().setAllowedRoots(List.of(base.toString()));
        return new PathGuard(guardProps);
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(MediaProperties.class)
    static class GuardContextConfig {

        @Bean
        PathGuard pathGuard() {
            return allowAllUnderBase();
        }

        @Bean
        MediaStorageGuard mediaStorageGuard(MediaProperties props, PathGuard pathGuard) {
            return new MediaStorageGuard(props, pathGuard);
        }
    }
}
