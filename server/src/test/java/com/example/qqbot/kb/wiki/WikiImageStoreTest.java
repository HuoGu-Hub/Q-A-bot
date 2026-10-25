package com.example.qqbot.kb.wiki;

import com.example.qqbot.config.KbProperties;
import com.example.qqbot.config.MediaProperties;
import com.example.qqbot.guard.FileAccess;
import com.example.qqbot.guard.PathGuard;
import com.example.qqbot.media.MediaStorageGuard;
import com.example.qqbot.media.UpstreamGate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 配图落盘的验收：路径、幂等、配额。**全程不联网** —— 走包内的 {@code store(...)}，
 * 网络那段只由 {@code WikiImageStore.fetch} 负责。
 */
class WikiImageStoreTest {

    @TempDir
    Path base;

    private MediaProperties media;
    private MediaStorageGuard storage;
    private WikiImageStore store;

    @BeforeEach
    void setUp() {
        media = new MediaProperties();
        media.getTempImages().setDir(base.resolve("tmp-images").toString());
        media.getKbImages().setDir(base.resolve("kb-images").toString());
        media.getWikiImages().setDir(base.resolve("wiki-images").toString());

        FileAccess fileAccess = new FileAccess();
        fileAccess.setAllowedRoots(List.of(base.toString()));
        storage = new MediaStorageGuard(media, new PathGuard(fileAccess));
        storage.init();

        KbProperties kb = new KbProperties();
        kb.getWikiImport().getImages().setEnabled(true);
        store = new WikiImageStore(kb, media, storage, new UpstreamGate(50, 0, 3, 50));
    }

    @Test
    @DisplayName("写入后返回 marker key，文件就在 wiki 目录下")
    void writesFileAndReturnsKey() {
        String page = WikiImageStore.pageSlug("Baby Common Goat");
        byte[] bytes = {1, 2, 3, 4};

        String key = store.store(page, "Baby Goat.png", "baby-goat", bytes, "image/png").orElseThrow();

        assertThat(key).isEqualTo("baby-common-goat/baby-goat.png");
        Path file = storage.wikiDir().resolve(key);
        assertThat(Files.isRegularFile(file)).isTrue();
    }

    @Test
    @DisplayName("重复写入同一页同一张图 → 复用，不产生第二个文件")
    void secondWriteReuses() throws Exception {
        String page = WikiImageStore.pageSlug("Baby Common Goat");
        String first = store.store(page, "Baby Goat.png", "baby-goat", new byte[] {1}, "image/png").orElseThrow();
        String second = store.store(page, "Baby Goat.png", "baby-goat", new byte[] {2}, "image/png").orElseThrow();

        assertThat(second).isEqualTo(first);
        assertThat(Files.size(storage.wikiDir().resolve(first))).isEqualTo(1);
    }

    @Test
    @DisplayName("配额到顶 → 停写，不淘汰已有文件")
    void quotaStopsNewWrites() {
        media.getWikiImages().setMaxTotalSizeMb(1);

        String key = store.store("baby-goat", "Baby Goat.png", "baby-goat",
                new byte[2 * 1024 * 1024], "image/png").orElse(null);

        assertThat(key).isNull();
        assertThat(Files.exists(storage.wikiDir().resolve("baby-goat/baby-goat.png"))).isFalse();
    }

    @Test
    @DisplayName("★ 非 ASCII 标题也有稳定且不冲突的目录名")
    void nonAsciiTitlesFallBackToHash() {
        String a = WikiImageStore.pageSlug("迷雾盆地");
        String b = WikiImageStore.pageSlug("水之觉醒");

        assertThat(a).startsWith("p");
        assertThat(b).startsWith("p");
        assertThat(a).isNotEqualTo(b);
        assertThat(WikiImageStore.stemOf("迷雾.png")).startsWith("img-");
    }

    @Test
    @DisplayName("MIME → 扩展名：SVG 不认，JPEG 统一成 jpg")
    void extensionMapping() {
        assertThat(WikiImageStore.extensionOf("image/png", "x.png")).isEqualTo("png");
        assertThat(WikiImageStore.extensionOf("image/jpeg", "x.jpg")).isEqualTo("jpg");
        assertThat(WikiImageStore.extensionOf("image/webp", "x.webp")).isEqualTo("webp");
        assertThat(WikiImageStore.extensionOf("image/svg+xml", "x.svg")).isNull();
    }

    @Test
    @DisplayName("下载地址带宽度参数，空格编码成下划线")
    void downloadUrl() {
        var images = new KbProperties().getWikiImport().getImages();

        String url = WikiImageStore.urlOf("Fell Dragon Youngling.png", images);

        assertThat(url).startsWith("https://enshrouded.wiki.gg/wiki/Special:FilePath/");
        assertThat(url).contains("Fell_Dragon_Youngling.png");
        assertThat(url).contains("width=480");
    }
}
