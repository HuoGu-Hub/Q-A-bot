package com.example.qqbot.onebot.outbound;

import com.example.qqbot.eval.EvalAlertRaised;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * 把评测告警私聊给运维 —— 没人盯着页面时，这是唯一会主动出声的通道。
 *
 * <p>收件人由 `app.eval.alerts.qq` 配置，逗号分隔；留空就只落事件。
 * 发送失败只记日志：告警已经进库，通知失败不该影响批次结论。
 */
@Component
public class EvalAlertNotifier {

    private static final Logger log = LoggerFactory.getLogger(EvalAlertNotifier.class);

    private final OutboundSender sender;
    private final List<Long> recipients;

    public EvalAlertNotifier(OutboundSender sender,
                             @Value("${app.eval.alerts.qq:}") String qqList) {
        this.sender = sender;
        this.recipients = Arrays.stream(qqList.split(","))
                .map(String::strip)
                .filter(s -> !s.isEmpty())
                .map(s -> {
                    try {
                        return Long.parseLong(s);
                    } catch (NumberFormatException e) {
                        log.warn("[EVAL] 告警收件人不是 QQ 号，已忽略：{}", s);
                        return null;
                    }
                })
                .filter(Objects::nonNull)
                .toList();
    }

    @EventListener
    public void onAlert(EvalAlertRaised alert) {
        if (recipients.isEmpty()) {
            return;
        }
        String text = "【评测告警】" + alert.ruleName()
                + "\n批次 #" + alert.batchId()
                + "\n" + label(alert.metric()) + "：" + value(alert.metric(), alert.value())
                + "（阈值 " + value(alert.metric(), alert.threshold()) + "）";
        for (Long qq : recipients) {
            try {
                sender.sendToPrivate(qq, text);
            } catch (RuntimeException e) {
                log.warn("[EVAL] 告警私聊发送失败 qq={}：{}", qq, e.getMessage());
            }
        }
    }

    private static String label(String metric) {
        return switch (metric) {
            case "passRate" -> "通过率";
            case "costPerRun" -> "单条成本";
            case "p95Ms" -> "P95 延迟";
            default -> metric;
        };
    }

    private static String value(String metric, double v) {
        return switch (metric) {
            case "passRate" -> Math.round(v * 1000) / 10.0 + "%";
            case "costPerRun" -> String.format("%.4f 元", v / 1_000_000);
            case "p95Ms" -> Math.round(v) + " ms";
            default -> String.valueOf(v);
        };
    }
}
