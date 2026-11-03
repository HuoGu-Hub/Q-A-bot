package com.example.qqbot.onebot.outbound;

import com.example.qqbot.eval.EvalAlertRaised;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 告警通知：有收件人才发，发送内容带批次与指标；没配收件人什么也不做。
 */
class EvalAlertNotifierTest {

    private final EvalAlertRaised alert = new EvalAlertRaised(7, "通过率过低", 12, "passRate",
            0.42, 0.8, "warn");

    @Test
    @DisplayName("配了收件人就把批次与指标私聊过去")
    void sendsToRecipients() {
        OutboundSender sender = mock(OutboundSender.class);
        new EvalAlertNotifier(sender, "10001, 10002").onAlert(alert);
        verify(sender).sendToPrivate(eq(10001L), contains("#12"));
        verify(sender).sendToPrivate(eq(10002L), contains("42.0%"));
    }

    @Test
    @DisplayName("没配收件人只落事件，不发消息")
    void silentWithoutRecipients() {
        OutboundSender sender = mock(OutboundSender.class);
        new EvalAlertNotifier(sender, "").onAlert(alert);
        verify(sender, never()).sendToPrivate(org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyString());
    }
}
