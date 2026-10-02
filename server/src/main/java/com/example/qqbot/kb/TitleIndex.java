package com.example.qqbot.kb;

/**
 * 标题向量的一次性补建（CLI）。
 *
 * <p>它只补"缺失"和"过期"（标题和生成时那份对不上）的块，**幂等**，
 * 所以随时可以再跑一遍，不会重复花钱。
 *
 * <pre>
 * mvn spring-boot:run -Dspring-boot.run.arguments="--app.kb.title-index.enabled=true --spring.main.web-application-type=none"
 * </pre>
 *
 * <h2>为什么不在 {@code config} 包里（2026-10-02 挪过来）</h2>
 * 它是<b>知识库自己的领域词汇</b>，只是碰巧从 {@code app.kb.*} 绑定过来。
 * 留在 {@code KbProperties} 的嵌套类里，会让 {@code kb} 包看起来"依赖配置的形状"。
 * 搬迁是**纯搬运**：零语义变化、零新抽象，yml 的键一个都没动。
 */
public class TitleIndex {

    private boolean enabled = false;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }
}
