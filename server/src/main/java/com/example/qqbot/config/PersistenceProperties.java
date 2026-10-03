package com.example.qqbot.config;

import com.example.qqbot.persistence.PersistencePolicy;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 数据库本身的配置 —— 对应 application.yml 里的 {@code app.persistence.*}。
 *
 * <h2>2026-10-02：从 {@code app.qa.*} 改名过来</h2>
 * 这两个键原先是 {@code app.qa.db} / {@code app.qa.enabled}，因为**问答记录是历史上
 * 第一个需要库的功能**。但它们描述的是<b>整个数据库</b>，而那个 SQLite 文件是
 * <b>多个功能共用的</b>：问答统计、指令表、词条表、分类、文案、轮播清单。
 * 挂在 {@code qa} 名下会让人以为"关掉问答记录"就能安全地不建库。
 *
 * <h2>⚠️ 这次改名**不是纯改名**，有一条行为变化，升级时要知道</h2>
 * 原先 {@code app.qa.enabled=false} 会<b>连带把数据库关掉</b>（{@code SqliteDatabase}
 * 读的就是它）。现在拆成两个独立的开关：
 * <ul>
 *   <li>{@link #isEnabled()}（{@code app.persistence.enabled}）—— 关掉后<b>不开库</b>，
 *       依赖库的功能（指令 / 词条 / 分类 / 文案 / 轮播）全部降级。默认 {@code true}。</li>
 *   <li>{@code app.qa.enabled} —— 只管<b>记不记录问答</b>，不再关库。</li>
 * </ul>
 * 这是修了一个真实的耦合：关掉"问答记录"不该让指令和轮播一起失效。
 *
 * <h2>⚠️ 从旧版本升级时请检查</h2>
 * 如果你在 systemd / 启动脚本里用命令行参数覆盖过路径，例如
 * <pre>--app.qa.db=/opt/qqbot/data/qqbot.sqlite</pre>
 * 请改成
 * <pre>--app.persistence.db=/opt/qqbot/data/qqbot.sqlite</pre>
 * 否则程序会<b>静默退回默认路径</b>（{@code ./data/qa/qqbot.sqlite}），
 * 看起来像"数据全没了"。启动日志里会打印实际打开的库路径（见 {@code StartupPaths}），
 * 升级后请核对一眼。
 */
@ConfigurationProperties(prefix = "app.persistence")
public class PersistenceProperties implements PersistencePolicy {

    /**
     * 数据库总开关。关掉后**不开库**，所有依赖库的功能都会降级（不是报错，是"没有数据"）。
     *
     * <p>只用于调试/排障。默认 {@code true}。
     */
    private boolean enabled = true;

    /**
     * SQLite 文件路径（相对路径以程序工作目录为基准）。
     *
     * <p><b>默认值和 {@code application.yml} 保持一致</b> —— 免得"配置文件丢了"时
     * 悄悄换到另一个库。
     */
    private String db = "./data/qa/qqbot.sqlite";

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    @Override
    public String getDb() {
        return db;
    }

    public void setDb(String db) {
        this.db = db;
    }
}
