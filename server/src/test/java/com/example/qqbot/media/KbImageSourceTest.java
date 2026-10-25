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
 * 知识库配图的验收测试。
 *
 * <p>最要紧的一条是**文件名白名单**：key 来自模型，而它会变成磁盘路径。
 * 放过去一个 {@code ../.env}，就等于让模型读服务器上的任意文件。
 */
class KbImageSourceTest {

    @TempDir
    Path base;

    private Path kbDir;
    private KbImageSource source;

    @BeforeEach
    void setUp() throws IOException {
        MediaProperties props = new MediaProperties();
        props.getTempImages().setDir(base.resolve("tmp").toString());
        props.getKbImages().setDir(base.resolve("kb").toString());
        props.getWikiImages().setDir(base.resolve("wiki-images").toString());

        FileAccess fileAccess = new FileAccess();
        fileAccess.setAllowedRoots(List.of(base.toString()));
        MediaStorageGuard storage = new MediaStorageGuard(props, new PathGuard(fileAccess));
        storage.init();

        kbDir = base.resolve("kb");
        source = new KbImageSource(storage);
        Files.write(kbDir.resolve("copper-vein.png"), new byte[] {1, 2, 3});
    }

    @Test
    @DisplayName("不带扩展名也能找到图：目录就是唯一索引")
    void resolvesByStem() {
        Optional<OutboundImage> image = source.resolve("copper-vein");

        assertThat(image).isPresent();
        assertThat(image.get().bytesMime()).isEqualTo("image/png");
        assertThat(image.get().byteSize()).isEqualTo(3);
    }

    @Test
    @DisplayName("带扩展名也行")
    void resolvesWithExtension() {
        assertThat(source.resolve("copper-vein.png")).isPresent();
    }

    @Test
    @DisplayName("目录里没有就返回 empty，不抛异常")
    void missingFileIsEmpty() {
        assertThat(source.resolve("does-not-exist")).isEmpty();
    }

    @Test
    @DisplayName("★ 目录穿越：斜杠与 .. 一律拒绝，绝不拼出目录外的路径")
    void rejectsTraversal() {
        assertThat(source.resolve("../.env")).isEmpty();
        assertThat(source.resolve("a/../../secret")).isEmpty();
        assertThat(source.resolve(".hidden")).isEmpty();
        assertThat(source.resolve("a b")).isEmpty();
    }
}