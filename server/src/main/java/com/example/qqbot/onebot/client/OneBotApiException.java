package com.example.qqbot.onebot.client;

/** 调用 NapCat 的 OneBot 接口失败 */
public class OneBotApiException extends RuntimeException {

    public OneBotApiException(String message) {
        super(message);
    }

    public OneBotApiException(String message, Throwable cause) {
        super(message, cause);
    }
}
