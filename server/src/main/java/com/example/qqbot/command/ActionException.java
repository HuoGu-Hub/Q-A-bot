package com.example.qqbot.command;

/**
 * 动作的<b>可预期</b>失败 —— 例如「编号 3 不是你建的」。
 *
 * <p>消息会被原样回给群友，所以它必须是人话；不需要用户知道的原因
 * 走日志与普通异常，不要塞进这里。
 */
public class ActionException extends Exception {

    public ActionException(String message) {
        super(message);
    }
}
