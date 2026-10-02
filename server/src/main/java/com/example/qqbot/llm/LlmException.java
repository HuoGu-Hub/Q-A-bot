package com.example.qqbot.llm;

/** 所有大模型 provider 都调用失败时抛出 */
public class LlmException extends RuntimeException {

    public LlmException(String message) {
        super(message);
    }
}
