package com.example.qqbot.admin;

import com.example.qqbot.config.PersistenceProperties;
import com.example.qqbot.onebot.outbound.OutboundSender;
import com.example.qqbot.persistence.Jdbc;
import com.example.qqbot.persistence.SqliteDatabase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class AdminQqCodeServiceTest {

    @TempDir
    Path tmp;

    private SqliteDatabase db;
    private AdminQqCodeService service;
    private OutboundSender outbound;

    @BeforeEach
    void setUp() {
        PersistenceProperties p = new PersistenceProperties();
        p.setDb(tmp.resolve("qq-code.sqlite").toString());
        db = new SqliteDatabase(p);
        db.init();
        Jdbc jdbc = new Jdbc(db);
        AdminQqCodeRepository repo = new AdminQqCodeRepository(jdbc);
        repo.init();
        outbound = mock(OutboundSender.class);
        service = new AdminQqCodeService(repo, outbound);
    }

    @AfterEach
    void tearDown() {
        db.close();
    }

    @Test
    @DisplayName("验证码只发一次，消费后立刻失效")
    void issueAndConsume() {
        AdminQqCodeService.Issued issued = service.issue(
                7L, "10001", AdminQqCodeService.PURPOSE_LOGIN, null);
        String code = sentCode();

        AdminQqCodeService.Consumed consumed = service.consume(
                issued.challengeId(), code, AdminQqCodeService.PURPOSE_LOGIN);
        assertThat(consumed.userId()).isEqualTo(7L);
        assertThat(consumed.qq()).isEqualTo("10001");

        assertThatThrownBy(() -> service.consume(
                issued.challengeId(), code, AdminQqCodeService.PURPOSE_LOGIN))
                .isInstanceOf(AdminQqCodeService.CodeException.class);
    }

    @Test
    @DisplayName("错误验证码被拒绝")
    void wrongCodeRejected() {
        AdminQqCodeService.Issued issued = service.issue(
                7L, "10001", AdminQqCodeService.PURPOSE_LOGIN, null);
        String actual = sentCode();
        String wrong = actual.equals("000000") ? "000001" : "000000";
        assertThatThrownBy(() -> service.consume(
                issued.challengeId(), wrong, AdminQqCodeService.PURPOSE_LOGIN))
                .isInstanceOf(AdminQqCodeService.CodeException.class);
    }

    private String sentCode() {
        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        verify(outbound).sendToPrivate(anyLong(), text.capture());
        Matcher m = Pattern.compile("(\\d{6})").matcher(text.getValue());
        assertThat(m.find()).isTrue();
        return m.group(1);
    }
}
