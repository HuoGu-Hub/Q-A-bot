package com.example.qqbot.distill;

import com.example.qqbot.config.DistillProperties;
import com.example.qqbot.config.PersistenceProperties;
import com.example.qqbot.media.KbImageWriter;
import com.example.qqbot.media.MediaStorageGuard;
import com.example.qqbot.persistence.ChatRepository;
import com.example.qqbot.persistence.DistillNoteRepository;
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
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DistillImageServiceTest {

    @TempDir
    Path base;

    private SqliteDatabase db;
    private ChatRepository chat;
    private DistillNoteRepository notes;
    private DistillImageService service;
    private Path kbDir;

    @BeforeEach
    void setUp() throws Exception {
        PersistenceProperties persist = new PersistenceProperties();
        persist.setDb(base.resolve("images.sqlite").toString());
        db = new SqliteDatabase(persist);
        db.init();
        Jdbc jdbc = new Jdbc(db);
        chat = new ChatRepository(jdbc);
        chat.initSchema();
        notes = new DistillNoteRepository(jdbc);
        notes.initSchema();

        DistillProperties props = new DistillProperties();
        props.setImagesDir(base.resolve("chat-images").toString());
        ChatImageStore chatImages = new ChatImageStore(props);
        kbDir = base.resolve("kb-images");
        MediaStorageGuard guard = mock(MediaStorageGuard.class);
        when(guard.kbDir()).thenReturn(kbDir);
        service = new DistillImageService(notes, chat, chatImages, new KbImageWriter(guard));
    }

    @AfterEach
    void close() {
        db.close();
    }

    @Test
    @DisplayName("发布时复制图片到 kb-images，正文追加标记；重复调用不重复")
    void copiesAndMarksOnce() throws Exception {
        byte[] bytes = "fake-png".getBytes("UTF-8");
        var stored = new ChatImageStore(new DistillProperties() {
            {
                setImagesDir(base.resolve("chat-images").toString());
            }
        }).save(bytes, "image/png");
        chat.insertImage(new ChatRepository.NewImage(1, stored.md5(), stored.filename(),
                stored.mime(), bytes.length, "", Instant.now().toString()));
        long chatImageId = chat.imagesOfMessage(1).get(0).id();

        var note = notes.insert(new DistillNoteRepository.NewNote(1, 1, "带图笔记",
                "带图笔记", "", "正文足够长，用来测试图片发布与标记追加，至少三十个字符。",
                "大佬攻略", 0.9, "draft", "test", "v1", "hash-image", Instant.now().toString()),
                List.of(), List.of(chatImageId));
        long noteId = note.id();

        String body = service.withImages(noteId, "正文足够长，用来测试图片发布与标记追加，至少三十个字符。");
        String filename = "distill-" + noteId + "-" + chatImageId + ".png";
        String marker = "[[img:kb:" + filename + "]]";
        assertThat(body).contains(marker);
        assertThat(body.split(java.util.regex.Pattern.quote(marker), -1)).hasSize(2);
        assertThat(Files.isRegularFile(kbDir.resolve(filename))).isTrue();

        String again = service.withImages(noteId, body);
        assertThat(again.split(java.util.regex.Pattern.quote(marker), -1)).hasSize(2);

        service.deleteImages(noteId);
        assertThat(Files.exists(kbDir.resolve(filename))).isFalse();
    }
}
