package com.example.qqbot.voice;

import com.example.qqbot.persistence.Jdbc;
import com.example.qqbot.persistence.SqliteDatabase;
import com.example.qqbot.persistence.VoiceAssetRepository;
import com.example.qqbot.config.PersistenceProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 素材库的验收：上传去重、格式与时长闸门、按标签挑选、删除连文件。
 *
 * <p>数据库用临时文件，ffprobe 换成假的 —— 这里验的是业务规则，不是转码本身。
 */
class VoiceLibraryTest {

    @TempDir
    Path tmp;

    private SqliteDatabase db;
    private VoiceLibrary library;
    private VoicePolicy policy;
    private VoiceProcessor processor;

    @BeforeEach
    void setUp() {
        PersistenceProperties props = new PersistenceProperties();
        props.setDb(tmp.resolve("voice.sqlite").toString());
        db = new SqliteDatabase(props);
        db.init();
        Jdbc jdbc = new Jdbc(db);

        Path voices = tmp.resolve("voices");
        policy = new VoicePolicy() {
            @Override public boolean isEnabled() { return true; }
            @Override public int getMaxKb() { return 1024; }
            @Override public int getMaxSeconds() { return 300; }
            @Override public String getFfmpeg() { return "ffmpeg"; }
            @Override public String getFfprobe() { return "ffprobe"; }
            @Override public int getTimeoutSeconds() { return 30; }
            @Override public String getVoicesDir() { return voices.toString(); }
        };
        processor = mock(VoiceProcessor.class);
        when(processor.probe(any())).thenReturn(12.5);

        library = new VoiceLibrary(new VoiceAssetRepository(jdbc), policy, processor);
        library.init();
    }

    @AfterEach
    void tearDown() {
        if (db != null) {
            db.close();
        }
    }

    @Test
    @DisplayName("上传：落盘、入库、时长按 ffprobe 记")
    void uploadsAndStores() {
        VoiceLibrary.Uploaded up = library.upload("晴天.mp3", "audio/mpeg",
                new byte[]{1, 2, 3, 4}, "晴天", "周杰伦,情歌", "歌曲,副歌");

        assertThat(up.duplicate()).isFalse();
        assertThat(up.asset().id()).isPositive();
        assertThat(up.asset().durationMs()).isEqualTo(12500);
        assertThat(up.asset().title()).isEqualTo("晴天");
        assertThat(Files.exists(library.pathOf(up.asset()))).isTrue();
        assertThat(library.list()).hasSize(1);
    }

    @Test
    @DisplayName("同一份内容重复上传：不落第二份文件，返回已有行")
    void deduplicatesByContent() {
        byte[] bytes = new byte[]{9, 8, 7};
        VoiceLibrary.Uploaded first = library.upload("a.mp3", "audio/mpeg", bytes, "A", "", "");
        VoiceLibrary.Uploaded second = library.upload("b.mp3", "audio/mpeg", bytes, "B", "", "");

        assertThat(second.duplicate()).isTrue();
        assertThat(second.asset().id()).isEqualTo(first.asset().id());
        assertThat(library.list()).hasSize(1);
    }

    @Test
    @DisplayName("格式、大小、时长三道闸门都在上传时拦")
    void rejectsBadUploads() {
        assertThatThrownBy(() -> library.upload("x.txt", "text/plain", new byte[]{1}, "", "", ""))
                .isInstanceOf(VoiceException.class).hasMessageContaining("不支持的格式");
        assertThatThrownBy(() -> library.upload("x.mp3", "audio/mpeg", new byte[0], "", "", ""))
                .isInstanceOf(VoiceException.class).hasMessageContaining("空的");

        when(processor.probe(any())).thenReturn(900.0);
        assertThatThrownBy(() -> library.upload("long.mp3", "audio/mpeg", new byte[]{1, 2}, "", "", ""))
                .isInstanceOf(VoiceException.class).hasMessageContaining("太长");
    }

    @Test
    @DisplayName("挑选：标题、别名、标签都能命中，空查询随机给一条")
    void picksByTitleAliasTag() {
        library.upload("qingtian.mp3", "audio/mpeg", new byte[]{1}, "晴天", "周杰伦", "歌曲");
        library.upload("meow.mp3", "audio/mpeg", new byte[]{2}, "猫叫", "喵", "音效");

        assertThat(library.pick("晴天")).isPresent();
        assertThat(library.pick("周杰伦")).isPresent();
        assertThat(library.pick("音效").orElseThrow().title()).isEqualTo("猫叫");
        assertThat(library.pick("不存在的")).isEmpty();
        assertThat(library.pick("")).isPresent();
    }

    @Test
    @DisplayName("删除：记录和磁盘文件一起消失")
    void deleteRemovesFileAndRow() {
        VoiceLibrary.Uploaded up = library.upload("a.mp3", "audio/mpeg", new byte[]{5}, "A", "", "");
        Path file = library.pathOf(up.asset());

        library.delete(up.asset().id());

        assertThat(Files.exists(file)).isFalse();
        assertThat(library.list()).isEmpty();
    }

    @Test
    @DisplayName("停用的素材不会被挑选，但仍然留在列表里")
    void disabledAssetsAreNotPicked() {
        VoiceLibrary.Uploaded up = library.upload("a.mp3", "audio/mpeg", new byte[]{6}, "A", "", "");
        library.update(up.asset().id(), null, null, null, false);

        assertThat(library.pick("A")).isEmpty();
        assertThat(library.list()).hasSize(1);
    }
}
