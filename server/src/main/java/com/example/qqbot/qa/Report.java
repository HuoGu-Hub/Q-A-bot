package com.example.qqbot.qa;

/**
 * 报表配置（S2）。和语料构建一样**默认关闭**，只有显式打开才跑：
 *
 * <pre>
 * mvn spring-boot:run -Dspring-boot.run.arguments="--app.qa.report.enabled=true --spring.main.web-application-type=none"
 * </pre>
 *
 * <h2>为什么不在 {@code config} 包里（2026-10-02 挪过来）</h2>
 * 它是<b>问答统计自己的领域词汇</b>（报表要看哪一段、看多少天），只是碰巧从
 * {@code app.qa.report.*} 绑定过来。留在 {@code QaProperties} 的嵌套类里，会让
 * {@code qa} 包看起来"依赖配置的形状"。
 * 搬迁是**纯搬运**：零语义变化，yml 的键一个都没动。
 */
public class Report {

    private boolean enabled = false;

    /** 统计最近多少天。0 = 全部 */
    private int days = 30;

    /** 看哪一段：all / overview / keywords / misses / cosine / sources */
    private String section = "all";

    /** 排行榜取前几名 */
    private int topN = 20;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getDays() {
        return days;
    }

    public void setDays(int days) {
        this.days = days;
    }

    public String getSection() {
        return section;
    }

    public void setSection(String section) {
        this.section = section;
    }

    public int getTopN() {
        return topN;
    }

    public void setTopN(int topN) {
        this.topN = topN;
    }
}
