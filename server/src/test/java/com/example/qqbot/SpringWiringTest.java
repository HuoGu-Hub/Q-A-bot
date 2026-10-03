package com.example.qqbot;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.stereotype.Component;

import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Spring **能不能把 Bean 装配起来** —— 在构建期检查，而不是等启动时炸。
 *
 * <h2>为什么需要它（真实故障）</h2>
 * {@code KbRetriever} 有两个 public 构造器（主构造 + 给测试用的便利构造），
 * 而 2026-10-02 的 cross-encoder 提交加第二个构造器时**忘了加 {@code @Autowired}**。
 *
 * <p>Spring 的规则是：<b>多个构造器且都没有 {@code @Autowired} → 退回找无参构造器</b>。
 * 找不到就 {@code No default constructor found} —— <b>整个应用起不来</b>。
 *
 * <p>而这个故障**骗过了所有测试**：368 个测试全绿、ArchUnit 12 条全绿，
 * 因为单元测试都是自己 {@code new} 的，根本不走 Spring 的装配。
 * 直到有人真的去启动服务才发现 —— 那时它已经起不来好几天了。
 *
 * <h2>为什么不用 @SpringBootTest</h2>
 * 那会真的起一遍应用：连 OneBot、开调度器、动数据库。慢、脆、还有副作用。
 * 这里只需要**Spring 自己那套扫描器** + 反射看构造器，毫秒级、零副作用。
 *
 * <p>⚠️ 本测试只覆盖 {@code @Component} 系列（靠构造器注入的）。
 * 用 {@code @Bean} 方法手工装配的类不受这条规则约束。
 */
class SpringWiringTest {

    /** Spring 认不认得出"该用哪个构造器" */
    private static boolean isAutowirable(Constructor<?>[] ctors) {
        List<Constructor<?>> real = new ArrayList<>();
        for (Constructor<?> c : ctors) {
            if (!c.isSynthetic() && Modifier.isPublic(c.getModifiers())) {
                real.add(c);
            }
        }
        if (real.size() <= 1) {
            return true;                       // 0 或 1 个 → Spring 都能处理
        }
        return real.stream().anyMatch(c -> c.isAnnotationPresent(Autowired.class));
    }

    @Test
    @DisplayName("★ 每个 @Component 的构造器都能被 Spring 选中（多构造器必须有 @Autowired）")
    void everyComponentHasAResolvableConstructor() throws Exception {
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(Component.class));

        List<String> broken = new ArrayList<>();
        int scanned = 0;
        for (BeanDefinition bd : scanner.findCandidateComponents("com.example.qqbot")) {
            Class<?> cls = Class.forName(bd.getBeanClassName());
            scanned++;
            if (!isAutowirable(cls.getDeclaredConstructors())) {
                broken.add(cls.getName() + "（" + cls.getDeclaredConstructors().length
                        + " 个构造器，没有一个标 @Autowired）");
            }
        }

        assertThat(scanned).as("扫描器本身要能工作 —— 扫不到类说明过滤条件写错了").isGreaterThan(50);
        assertThat(broken)
                .as("这些 Bean Spring 装配不了，应用会以 'No default constructor found' 启动失败：%s", broken)
                .isEmpty();
    }

    @Test
    @DisplayName("★ 这个检查本身有效 —— 它认得出坏形状（防止它变成一条永远绿的假测试）")
    void theCheckActuallyDetectsTheBrokenShape() throws Exception {
        // 两个 public 构造器、都没 @Autowired → 必须判为"装配不了"
        assertThat(isAutowirable(FakeBroken.class.getDeclaredConstructors())).isFalse();
        // 标了 @Autowired → 判为"可以"
        assertThat(isAutowirable(FakeFixed.class.getDeclaredConstructors())).isTrue();
        // 只有一个 → 可以（Spring 4.3+ 单构造器免标注）
        assertThat(isAutowirable(FakeSingle.class.getDeclaredConstructors())).isTrue();
    }

    @SuppressWarnings("unused")
    static class FakeBroken {
        public FakeBroken(String a) {
        }

        public FakeBroken(String a, String b) {
        }
    }

    @SuppressWarnings("unused")
    static class FakeFixed {
        @Autowired
        public FakeFixed(String a) {
        }

        public FakeFixed(String a, String b) {
        }
    }

    @SuppressWarnings("unused")
    static class FakeSingle {
        public FakeSingle(String a) {
        }
    }
}
