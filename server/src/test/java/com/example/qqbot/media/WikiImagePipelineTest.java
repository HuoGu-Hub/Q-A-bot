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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 从"资料正文里的 marker"到"一张可发送的图"的整条链路验收。
 *
 * <p>这条链路跨三个组件：{@link ImageMarkers} 摘标记、{@link ImageLibrary} 分派、
 * {@link WikiImageSource} 读盘。任何一环少一个来源前缀，表现都是"资料里有图，群里没有"，
 * 而且不报错 —— 所以用一条端到端断言钉住它。
 */
class WikiImagePipelineTest {

    @TempDir
    Path base;

    private MediaStorageGuard storage;

    @BeforeEach
    void setUp() {
        MediaProperties props = new MediaProperties();
        props.getTempImages().setDir(base.resolve("tmp-images").toString());
        props.getKbImages().setDir(base.resolve("kb-images").toString());
        props.getWikiImages().setDir(base.resolve("wiki-images").toString());

        FileAccess fileAccess = new FileAccess();
        fileAccess.setAllowedRoots(List.of(base.toString()));
        storage = new MediaStorageGuard(props, new PathGuard(fileAccess));
        storage.init();
    }

    @Test
    @DisplayName("★ [[img:wiki:...]] → 摘掉标记 → 解析成真实字节")
    void markerBecomesImageBytes() throws IOException {
        Path file = storage.wikiDir().resolve("fell-dragon/fell-dragon.png");
        Files.createDirectories(file.getParent());
        Files.write(file, new byte[] {(byte) 0x89, 'P', 'N', 'G', 1, 2, 3});

        ImageMarkers.Parsed parsed = ImageMarkers.parse("它长这样 [[img:wiki:fell-dragon/fell-dragon.png]] 很好认");
        assertThat(parsed.text()).doesNotContain("[[img:");

        ImageLibrary library = new ImageLibrary(new MediaProperties(), List.of(new WikiImageSource(storage)));
        List<OutboundImage> images = library.resolveAll(parsed.keys());

        assertThat(images).hasSize(1);
        assertThat(images.get(0).bytesMime()).isEqualTo("image/png");
        assertThat(images.get(0).byteSize()).isEqualTo(7);
    }

    @Test
    @DisplayName("不存在的 key 只丢图，不影响正文")
    void missingKeyIsDropped() {
        ImageMarkers.Parsed parsed = ImageMarkers.parse("正文 [[img:wiki:no-such/page.png]]");

        ImageLibrary library = new ImageLibrary(new MediaProperties(), List.of(new WikiImageSource(storage)));

        assertThat(library.resolveAll(parsed.keys())).isEmpty();
        assertThat(parsed.text()).isEqualTo("正文");
    }
}
