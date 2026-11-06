package com.example.qqbot.config;

import com.example.qqbot.intent.IntentPolicy;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 意图判定配置 —— 对应 {@code app.intent.*}。
 *
 * <p>各项都是**热生效**的：{@link com.example.qqbot.intent.IntentPlanner} 每轮现读，
 * 后台「设置 → 意图」改完，下一条消息就按新值走，不用重启。
 */
@ConfigurationProperties(prefix = "app.intent")
public class IntentProperties implements IntentPolicy {

    /** 总开关。关掉之后判定不参与，行为与接入前完全一致 */
    private boolean enabled = true;

    /** 社交整活不检索 */
    private boolean skipSocial = true;

    /** 关于机器人自己不检索 */
    private boolean skipMeta = true;

    /** 做不到的请求不检索 */
    private boolean skipOutOfScope = true;

    /** 识别纠正（第三轮提交生效） */
    private boolean correction = true;

    /** 回看几轮 */
    private int recentTurns = 3;

    /** 上一轮多久之内还算数 */
    private int recentMinutes = 30;

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    @Override
    public boolean isSkipSocial() {
        return skipSocial;
    }

    public void setSkipSocial(boolean skipSocial) {
        this.skipSocial = skipSocial;
    }

    @Override
    public boolean isSkipMeta() {
        return skipMeta;
    }

    public void setSkipMeta(boolean skipMeta) {
        this.skipMeta = skipMeta;
    }

    @Override
    public boolean isSkipOutOfScope() {
        return skipOutOfScope;
    }

    public void setSkipOutOfScope(boolean skipOutOfScope) {
        this.skipOutOfScope = skipOutOfScope;
    }

    @Override
    public boolean isCorrection() {
        return correction;
    }

    public void setCorrection(boolean correction) {
        this.correction = correction;
    }

    @Override
    public int getRecentTurns() {
        return recentTurns;
    }

    public void setRecentTurns(int recentTurns) {
        this.recentTurns = recentTurns;
    }

    @Override
    public int getRecentMinutes() {
        return recentMinutes;
    }

    public void setRecentMinutes(int recentMinutes) {
        this.recentMinutes = recentMinutes;
    }
}
