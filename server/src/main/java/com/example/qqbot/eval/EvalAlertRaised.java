package com.example.qqbot.eval;

/**
 * 告警命中事件。
 *
 * <p>用 Spring 事件而不是直接调发送器：批次线程不该等网络，发送失败也不能
 * 让"告警已经落库"这件事回滚。监听方在 outbound 包里，评测侧不认识 OneBot。
 */
public record EvalAlertRaised(long ruleId, String ruleName, long batchId, String metric,
                              double value, double threshold, String severity) {
}
