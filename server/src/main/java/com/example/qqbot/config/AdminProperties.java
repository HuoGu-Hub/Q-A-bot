package com.example.qqbot.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 管理后台配置，对应 application.yml 里的 app.admin.*
 *
 * <p><b>安全默认：没配密码 = 后台完全不可访问。</b>
 * 宁可打不开，也不能裸奔 —— 这个后台能看到群里的提问原文。
 */
@ConfigurationProperties(prefix = "app.admin")
public class AdminProperties {

    /** 总开关 */
    private boolean enabled = true;

    /**
     * 管理员密码。从环境变量 ADMIN_PASSWORD 读。
     *
     * <p><b>留空 = 后台关闭</b>（所有 /admin 请求返回 503），不会退化成"无密码可访问"。
     */
    private String password = "";

    /** 登录会话有效期（小时） */
    private int sessionHours = 24;

    /** 记录后台访问 */
    private boolean recordVisits = true;

    /**
     * 登录失败保护：同一来源在 {@link #loginFailureWindowSeconds} 秒内失败这么多
     * 次，就锁定 {@link #loginLockSeconds} 秒。**0 = 关闭保护**。
     *
     * <p>为什么要这个：后台是会挂到公网（内网穿透）上的，密码就是唯一的门锁。
     * 没有失败限制的话，脚本可以全速穷举。
     */
    private int loginMaxFailures = 5;

    /** 失败计数的统计窗口（秒）。窗口内没继续失败，计数从头来 */
    private int loginFailureWindowSeconds = 600;

    /** 触发后锁定时长（秒） */
    private int loginLockSeconds = 600;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public int getSessionHours() {
        return sessionHours;
    }

    public void setSessionHours(int sessionHours) {
        this.sessionHours = sessionHours;
    }

    public boolean isRecordVisits() {
        return recordVisits;
    }

    public void setRecordVisits(boolean recordVisits) {
        this.recordVisits = recordVisits;
    }

    public int getLoginMaxFailures() {
        return loginMaxFailures;
    }

    public void setLoginMaxFailures(int loginMaxFailures) {
        this.loginMaxFailures = loginMaxFailures;
    }

    public int getLoginFailureWindowSeconds() {
        return loginFailureWindowSeconds;
    }

    public void setLoginFailureWindowSeconds(int loginFailureWindowSeconds) {
        this.loginFailureWindowSeconds = loginFailureWindowSeconds;
    }

    public int getLoginLockSeconds() {
        return loginLockSeconds;
    }

    public void setLoginLockSeconds(int loginLockSeconds) {
        this.loginLockSeconds = loginLockSeconds;
    }
}
