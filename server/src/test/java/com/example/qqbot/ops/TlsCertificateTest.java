package com.example.qqbot.ops;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 证书监控的验收。重点在**读不到时不能抛异常** ——
 * 一条群消息不该因为证书文件不见了就发不出去。
 */
class TlsCertificateTest {

    @Test
    @DisplayName("没配路径时返回空，不抛异常")
    void blankPathMeansEmpty() {
        TlsCertificate cert = new TlsCertificate(() -> "");

        assertThat(cert.current()).isEmpty();
    }

    @Test
    @DisplayName("文件不存在时返回空，不抛异常")
    void missingFileMeansEmpty() {
        TlsCertificate cert = new TlsCertificate(() -> "/definitely/not/here/qqbot.crt");

        assertThat(cert.current()).isEmpty();
    }

    @Test
    @DisplayName("★ 状态文案：正常 / 临期 / 已过期")
    void statusText() {
        assertThat(info(89).status()).isEqualTo("正常（还剩 89 天）");
        assertThat(info(7).status()).isEqualTo("⚠️ 仅剩 7 天");
        assertThat(info(0).status()).isEqualTo("⚠️ 仅剩 0 天");
        assertThat(info(-3).status()).isEqualTo("已过期 3 天");
    }

    @Test
    @DisplayName("剩余天数按整数天输出")
    void daysText() {
        assertThat(info(30).daysText()).isEqualTo("30");
        assertThat(info(-1).daysText()).isEqualTo("-1");
    }

    private static TlsCertificate.Info info(long days) {
        Instant notAfter = Instant.now().plusSeconds(days * 86400);
        return new TlsCertificate.Info("CN=test", Instant.now(), notAfter, days, "2027-01-26");
    }
}
