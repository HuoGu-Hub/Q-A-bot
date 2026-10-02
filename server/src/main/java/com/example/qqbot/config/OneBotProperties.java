package com.example.qqbot.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * OneBot / NapCat 相关配置，对应 application.yml 里的 app.onebot.*
 */
@ConfigurationProperties(prefix = "app.onebot")
public class OneBotProperties {

    /** NapCat 的 HTTP 接口地址，例如 http://127.0.0.1:3000 */
    private String apiBase = "http://127.0.0.1:3000";

    /** 与 NapCat「网络配置 → HTTP服务器」里的 Token 保持一致；留空表示不鉴权 */
    private String accessToken = "";

    /** 调用 NapCat 接口的超时时间（毫秒） */
    private int requestTimeoutMs = 10_000;

    public String getApiBase() {
        return apiBase;
    }

    public void setApiBase(String apiBase) {
        this.apiBase = apiBase;
    }

    public String getAccessToken() {
        return accessToken;
    }

    public void setAccessToken(String accessToken) {
        this.accessToken = accessToken;
    }

    public int getRequestTimeoutMs() {
        return requestTimeoutMs;
    }

    public void setRequestTimeoutMs(int requestTimeoutMs) {
        this.requestTimeoutMs = requestTimeoutMs;
    }
}
