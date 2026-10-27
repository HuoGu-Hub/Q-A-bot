package com.example.qqbot.distill;

import com.example.qqbot.config.PersistenceProperties;
import com.example.qqbot.persistence.ChatRepository;
import com.example.qqbot.persistence.DistillBlocklistRepository;
import com.example.qqbot.persistence.Jdbc;
import com.example.qqbot.persistence.SqliteDatabase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DistillBlocklistTest {

    @TempDir
    Path base;

    private SqliteDatabase db;
    private ChatRepository chat;
    private DistillBlocklistRepository blocklist;

    @BeforeEach
    void setUp() {
        PersistenceProperties props = new PersistenceProperties();
        props.setDb(base.resolve("blocklist.sqlite").toString());
        db = new SqliteDatabase(props);
        db.init();
        Jdbc jdbc = new Jdbc(db);
        chat = new ChatRepository(jdbc);
        chat.initSchema();
        blocklist = new DistillBlocklistRepository(jdbc);
        blocklist.initSchema();

        chat.insertMessageIfAbsent(new ChatRepository.NewMessage(
                "live", 0, 100, 1, "2026-10-20T10:00:00Z", senderHash(100, "20001"),
                "20001", "AI助手", "这是 AI 的消息", 0, "raw-ai", "2026-10-20T10:00:00Z"));
        chat.insertMessageIfAbsent(new ChatRepository.NewMessage(
                "live", 0, 100, 2, "2026-10-20T10:01:00Z", "human", "20002", "群友", "这是群友的消息", 0,
                "raw-human", "2026-10-20T10:01:00Z"));
    }

    @AfterEach
    void close() {
        db.close();
    }

    private static String senderHash(long groupId, String qq) {
        try {
            var md = java.security.MessageDigest.getInstance("SHA-256");
            return java.util.HexFormat.of().formatHex(md.digest(
                    (groupId + ":" + qq).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    @DisplayName("禁学成员的消息不会进入自动蒸馏和任务查询")
    void blockedSenderIsExcluded() {
        assertThat(chat.messagesAfter(0, List.of(), 10)).hasSize(2);

        blocklist.add(senderHash(100, "20001"), "AI助手");

        assertThat(chat.messagesAfter(0, List.of(), 10))
                .extracting(ChatRepository.Message::senderHash)
                .containsExactly("human");
        assertThat(chat.countAfter(0, List.of())).isEqualTo(1);
        assertThat(chat.messagesBetween(1, 2))
                .extracting(ChatRepository.Message::senderHash)
                .containsExactly("human");
        assertThat(chat.candidates("live", List.of(), "", "", 0)).hasSize(1);
    }

    @Test
    @DisplayName("按 QQ 号屏蔽会回填历史消息并排除后续消息")
    void blocksByQqAndBackfillsHistory() {
        assertThat(blocklist.blockQq("20001", "AI助手")).isEqualTo(1);

        assertThat(chat.messagesAfter(0, List.of(), 10))
                .extracting(ChatRepository.Message::senderHash)
                .containsExactly("human");
        assertThat(chat.candidates("live", List.of(), "", "", 0)).hasSize(1);

        blocklist.removeQq("20001");
        assertThat(chat.messagesAfter(0, List.of(), 10)).hasSize(2);
    }
}
