package com.example.qqbot.onebot.client;

/** 调用 NapCat 的 OneBot 接口失败 */
public class OneBotApiException extends RuntimeException {

    /** true = 请求可能已经送达，只是没拿到响应；调用方不能直接重发 */
    private final boolean transportFailure;

    public OneBotApiException(String message) {
        this(message, null, false);
    }

    public OneBotApiException(String message, Throwable cause) {
        this(message, cause, true);
    }

    public OneBotApiException(String message, Throwable cause, boolean transportFailure) {
        super(message, cause);
        this.transportFailure = transportFailure;
    }

    public boolean isTransportFailure() {
        return transportFailure;
    }
}
