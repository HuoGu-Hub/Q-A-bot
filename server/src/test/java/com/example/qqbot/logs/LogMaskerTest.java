package com.example.qqbot.logs;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 文件、journal、内存日志都走同一个脱敏入口；这里锁住静态入口，
 * 防止以后只改后台日志页、忘了落盘日志。
 */
class LogMaskerTest {

    @Test
    @DisplayName("静态入口默认脱敏 token 与 QQ 号")
    void masksSecretsAndQq() {
        String out = LogMasker.maskStatic("token=abcdefghijklmn user=123456789");
        assertThat(out).doesNotContain("abcdefghijklmn");
        assertThat(out).doesNotContain("123456789");
        assertThat(out).contains("abc***lmn");
        assertThat(out).contains("***6789");
    }

    @Test
    @DisplayName("脱敏后的文本再次脱敏不会破坏内容")
    void idempotent() {
        String once = LogMasker.maskStatic("Authorization: Bearer abcdefghijklmn");
        assertThat(LogMasker.maskStatic(once)).isEqualTo(once);
    }
}
