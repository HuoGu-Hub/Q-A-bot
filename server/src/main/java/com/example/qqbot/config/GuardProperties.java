package com.example.qqbot.config;

import com.example.qqbot.guard.Access;
import com.example.qqbot.guard.Budget;
import com.example.qqbot.guard.ContentGate;
import com.example.qqbot.guard.FileAccess;
import com.example.qqbot.guard.GuardSwitch;
import com.example.qqbot.guard.Outbound;
import com.example.qqbot.guard.RateLimit;
import com.example.qqbot.guard.Words;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 安全中间层的配置绑定 —— 对应 application.yml 里的 {@code app.guard.*}。
 *
 * <p>设计原则：<b>每一层都能单独开关</b>，出问题时可以逐层关闭来定位。
 *
 * <h2>2026-10-02：7 个分组搬去了 {@code guard} 包，本类只剩「绑定」</h2>
 * {@code Access} / {@code RateLimit} / {@code Budget} / {@code Outbound} /
 * {@code ContentGate} / {@code Words} / {@code FileAccess} 原先是本类的嵌套类。
 * 它们是<b>安全中间层的领域词汇</b>，只是碰巧从 yml 绑定过来 —— 留在 config 里会让
 * {@code guard} 包看起来"依赖配置的形状"，而实际上 13 个消费者早就通过
 * {@code GuardConfig} 的 bean 直接注入它们了。
 *
 * <p>搬迁是**纯搬运**：yml 的键一个都没变（{@code app.guard.rate-limit.*} 照旧），
 * 绑定关系也没变 —— 本类仍然持有那 7 个对象、仍然由 Spring 填值。
 *
 * <p>根上的两条开关（{@code enabled} / {@code killSwitch}）**没有搬**：
 * 它们的键就在根上，挪成嵌套对象会改键名、破坏现网配置。改成实现
 * {@link GuardSwitch} 这个两方法的只读接口。
 */
@ConfigurationProperties(prefix = "app.guard")
public class GuardProperties implements GuardSwitch {

    /** 总开关。关掉后所有限制失效（调试用） */
    private boolean enabled = true;

    /**
     * 应急开关。设为 true 后机器人**完全不回复**（连兜底词都不回）。
     * 出事的时候你需要一条命令让它立刻闭嘴。
     */
    private boolean killSwitch = false;

    private Access access = new Access();
    private RateLimit rateLimit = new RateLimit();
    private Outbound outbound = new Outbound();
    private Budget budget = new Budget();
    private ContentGate contentGate = new ContentGate();
    private Words words = new Words();
    private FileAccess fileAccess = new FileAccess();

    // ==================== getter / setter ====================

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    @Override
    public boolean isKillSwitch() {
        return killSwitch;
    }

    public void setKillSwitch(boolean killSwitch) {
        this.killSwitch = killSwitch;
    }

    public Access getAccess() {
        return access;
    }

    public void setAccess(Access access) {
        this.access = access;
    }

    public RateLimit getRateLimit() {
        return rateLimit;
    }

    public void setRateLimit(RateLimit rateLimit) {
        this.rateLimit = rateLimit;
    }

    public Budget getBudget() {
        return budget;
    }

    public void setBudget(Budget budget) {
        this.budget = budget;
    }

    public Outbound getOutbound() {
        return outbound;
    }

    public void setOutbound(Outbound outbound) {
        this.outbound = outbound;
    }

    public ContentGate getContentGate() {
        return contentGate;
    }

    public void setContentGate(ContentGate contentGate) {
        this.contentGate = contentGate;
    }

    public Words getWords() {
        return words;
    }

    public void setWords(Words words) {
        this.words = words;
    }

    public FileAccess getFileAccess() {
        return fileAccess;
    }

    public void setFileAccess(FileAccess fileAccess) {
        this.fileAccess = fileAccess;
    }
}

