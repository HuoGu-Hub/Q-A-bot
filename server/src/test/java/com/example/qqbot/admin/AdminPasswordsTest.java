package com.example.qqbot.admin;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 密码哈希的验收：同一密码两次结果不同（有盐）、错误密码不通过、脏数据不抛异常。
 */
class AdminPasswordsTest {

    @Test
    @DisplayName("同样的密码两次哈希结果不同（每份独立盐）")
    void hashIsSalted() {
        String a = AdminPasswords.hash("correct horse");
        String b = AdminPasswords.hash("correct horse");

        assertThat(a).isNotEqualTo(b);
        assertThat(AdminPasswords.verify("correct horse", a)).isTrue();
        assertThat(AdminPasswords.verify("correct horse", b)).isTrue();
    }

    @Test
    @DisplayName("错误密码、空密码一律不通过")
    void rejectsWrong() {
        String stored = AdminPasswords.hash("s3cret-pass");

        assertThat(AdminPasswords.verify("s3cret-pas", stored)).isFalse();
        assertThat(AdminPasswords.verify("", stored)).isFalse();
        assertThat(AdminPasswords.verify(null, stored)).isFalse();
        assertThat(AdminPasswords.verify("s3cret-pass", null)).isFalse();
    }

    @Test
    @DisplayName("★ 脏数据不抛异常，只返回 false")
    void malformedStoredDoesNotThrow() {
        for (String bad : new String[]{"", "plain-text", "pbkdf2$abc$x$y", "pbkdf2$0$x$y",
                "bcrypt$1$a$b", "pbkdf2$1000$%%%$%%%"}) {
            assertThat(AdminPasswords.verify("whatever", bad)).as(bad).isFalse();
        }
    }

    @Test
    @DisplayName("空密码不允许哈希（写入路径必须显式报错）")
    void refusesEmptyInput() {
        try {
            AdminPasswords.hash("");
            org.assertj.core.api.Assertions.fail("空密码应当被拒绝");
        } catch (IllegalArgumentException expected) {
            assertThat(expected.getMessage()).contains("不能为空");
        }
    }
}
