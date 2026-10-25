package com.example.qqbot.media;

import com.example.qqbot.config.MediaProperties;
import com.example.qqbot.guard.FileAccess;
import com.example.qqbot.guard.PathGuard;
import com.example.qqbot.onebot.model.OutboundImage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * wiki 配图的出站解析：key 来自模型，最终会拼成磁盘路径 —— 白名单是第一道，路径归属是第二道。
 */
class WikiImageSourceTest {

    @TempDir
    Path base;

    private MediaStorageGuard storage;
    private WikiImageSource source;

    @BeforeEach
    void setUp() throws IOException {
        MediaProperties props = new MediaProperties();
        props.getTempImages().setDir(base.resolve("tmp-images").toString());
        props.getKbImages().setDir(base.resolve("kb-images").toString());
        props.getWikiImages().setDir(base.resolve("wiki-images").toString());

        FileAccess fileAccess = new FileAccess();
        fileAccess.setAllowedRoots(List.of(base.toString()));
        storage = new MediaStorageGuard(props, new PathGuard(fileAccess));
        storage.init();

        source = new WikiImageSource(storage);
        Path file = storage.wikiDir().resolve("baby-common-goat/baby-goat.png");
        Files.createDirectories(file.getParent());
        Files.write(file, new byte[] {1, 2, 3});
    }

    @Test
    @DisplayName("按键取图：page/文件名，MIME 由扩展名决定")
    void resolvesKey() {
        Optional<OutboundImage> image = source.resolve("baby-common-goat/baby-goat.png");

        assertThat(image).isPresent();
        assertThat(image.get().bytesMime()).isEqualTo("image/png");
        assertThat(image.get().byteSize()).isEqualTo(3);
    }

    @Test
    @DisplayName("目录里没有就返回 empty，不抛异常")
    void missingIsEmpty() {
        assertThat(source.resolve("baby-common-goat/no-such.png")).isEmpty();
    }

    @Test
    @DisplayName("★ 目录穿越：..、多余层级、隐藏文件、非图片扩展名一律拒绝")
    void rejectsUnsafeKeys() {
        assertThat(source.resolve("../kb-images/secret.png")).isEmpty();
        assertThat(source.resolve("a/../b.png")).isEmpty();
        assertThat(source.resolve(".hidden/x.png")).isEmpty();
        assertThat(source.resolve("page/evil.exe")).isEmpty();
        assertThat(source.resolve("page/noext")).isEmpty();
    }
}
