package com.example.qqbot.guard;

/**
 * 成本预算 —— 管的是「今天总共能用多少」，和 {@link RateLimit}（管「多快」）互补。
 *
 * <p>为什么光有限流不够：限流挡不住「细水长流」。每人每分钟 1 次听着很克制，
 * 一天也能问 1440 次；群里人多一点，token 账单就起来了。所以需要一层
 * <b>额度</b>（按天归零），而不是只有频率。
 *
 * <p>所有额度都是 <b>0 = 不限</b>，所以默认配置完全不改变现有行为。
 *
 * <h2>为什么不在 {@code config} 包里（2026-10-02 挪过来）</h2>
 * 它是<b>安全中间层的领域词汇</b>，只是碰巧从 {@code app.guard.*} 绑定过来。
 * 留在 {@code GuardProperties} 的嵌套类里，会让 {@code guard} 包看起来"依赖配置的形状" ——
 * 而 13 个消费者早就通过 {@code GuardConfig} 的 bean 直接注入它了，根本没有那回事。
 * 搬迁是**纯搬运**：零语义变化，只是把"这个形状归谁"摆正。
 */
public class Budget {

    private boolean enabled = true;

    /** 每个用户每天最多被回复几次。0 = 不限 */
    private int perUserPerDay = 0;

    /** 每个群每天最多被回复几次。0 = 不限。超了该群<b>静默</b> */
    private int perGroupPerDay = 0;

    /** 全局每天最多回复几次。0 = 不限。超了<b>熔断</b>（全都不回 + 告警） */
    private int globalPerDay = 0;

    /** 全局每天最多消耗多少 token（输入+输出）。0 = 不限 */
    private long globalTokensPerDay = 0;

    /** 单用户触顶时回的话。{limit} = 该用户当日额度 */
    private String userLimitText = "今天你已经问过我 {limit} 次啦，明天再来找我玩吧～";


    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getPerUserPerDay() {
        return perUserPerDay;
    }

    public void setPerUserPerDay(int perUserPerDay) {
        this.perUserPerDay = perUserPerDay;
    }

    public int getPerGroupPerDay() {
        return perGroupPerDay;
    }

    public void setPerGroupPerDay(int perGroupPerDay) {
        this.perGroupPerDay = perGroupPerDay;
    }

    public int getGlobalPerDay() {
        return globalPerDay;
    }

    public void setGlobalPerDay(int globalPerDay) {
        this.globalPerDay = globalPerDay;
    }

    public long getGlobalTokensPerDay() {
        return globalTokensPerDay;
    }

    public void setGlobalTokensPerDay(long globalTokensPerDay) {
        this.globalTokensPerDay = globalTokensPerDay;
    }

    public String getUserLimitText() {
        return userLimitText;
    }

    public void setUserLimitText(String userLimitText) {
        this.userLimitText = userLimitText;
    }
}
