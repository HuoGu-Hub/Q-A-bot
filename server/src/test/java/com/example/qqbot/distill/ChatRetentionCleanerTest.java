package com.example.qqbot.distill;

import com.example.qqbot.config.PersistenceProperties;
import com.example.qqbot.persistence.ChatRepository;
import com.example.qqbot.persistence.Jdbc;
import com.example.qqbot.persistence.SqliteDatabase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 保留期清理：旧消息与旧图片要真的消失，新数据不能误删。
 */
class ChatRetentionCleanerTest {

    @TempDir
    Path tmp;

    private SqliteDatabase db;
    private ChatRepository repo;
    private ChatImageStore images;
    private ChatRetentionCleaner cleaner;

    @BeforeEach
    void setUp() {
        PersistenceProperties props = new PersistenceProperties();
        props.setDb(tmp.resolve("chat.sqlite").toString());
        db = new SqliteDatabase(props);
        db.init();
        repo = new ChatRepository(new Jdbc(db));
        repo.initSchema();

        DistillPolicy policy = mock(DistillPolicy.class);
        when(policy.getImagesDir()).thenReturn(tmp.resolve("chat-images").toString());
        when(policy.getMaxImageSizeKb()).thenReturn(1024);
        when(policy.getImageRetentionDays()).thenReturn(10);
        when(policy.getMessageRetentionDays()).thenReturn(30);
        images = new ChatImageStore(policy);
        cleaner = new ChatRetentionCleaner(repo, policy, images);
    }

    @AfterEach
    void tearDown() {
        if (db != null) {
            db.close();
        }
    }

    @Test
    @DisplayName("超过保留期的消息、图片行和磁盘文件一起删除")
    void deletesOldRowsAndFiles() throws Exception {
        String old = Instant.now().minus(40, ChronoUnit.DAYS).toString();
        String fresh = Instant.now().toString();

        long oldId = repo.insertMessageIfAbsent(new ChatRepository.NewMessage(
                "live", 0, 1, 1, old, "h-old", "u-old", "old", "旧消息", 1,
                "hash-old", old)).id();
        long freshId = repo.insertMessageIfAbsent(new ChatRepository.NewMessage(
                "live", 0, 1, 2, fresh, "h-new", "u-new", "new", "新消息", 0,
                "hash-new", fresh)).id();

        ChatImageStore.Stored stored = images.save(new byte[]{(byte) 0xFF, (byte) 0xD8, 1},
                "image/jpeg");
        repo.insertImage(new ChatRepository.NewImage(oldId, stored.md5(), stored.filename(),
                stored.mime(), stored.bytes(), "", old));

        Path file = images.dir().resolve(stored.filename());
        assertThat(Files.exists(file)).isTrue();

        ChatRetentionCleaner.Cleanup result = cleaner.runOnce(Instant.now());

        assertThat(result.imageRows()).isEqualTo(1);
        assertThat(result.imageFiles()).isEqualTo(1);
        assertThat(result.messageRows()).isEqualTo(1);
        assertThat(repo.findMessage(oldId)).isNull();
        assertThat(repo.findMessage(freshId)).isNotNull();
        assertThat(repo.countImages()).isZero();
        assertThat(Files.exists(file)).isFalse();
    }
}
