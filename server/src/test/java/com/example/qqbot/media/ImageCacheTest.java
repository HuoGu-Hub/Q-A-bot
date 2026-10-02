package com.example.qqbot.media;

import com.example.qqbot.config.GuardProperties;
import com.example.qqbot.config.MediaProperties;
import com.example.qqbot.guard.PathGuard;
import com.example.qqbot.onebot.model.ImageRef;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 图片文件缓存的验收测试。
 *
 * <p>核心要证明的一件事：**同一张图第二次不再下载**。
 */
class ImageCacheTest {

    private static final String KEY = "65a82be1ae2810aeb287d78c2260c823";
    private static final byte[] BYTES = "fake-jpeg-bytes".getBytes();

    @TempDir
    Path base;

    private MediaProperties props;
    private MediaStorageGuard storage;
    private PathGuard pathGuard;
    private ImageCache cache;

    @BeforeEach
    void setUp() {
        props = new MediaProperties();
        props.getTempImages().setDir(base.resolve("data/tmp-images").toString());
        props.getKbImages().setDir(base.resolve("data/kb-images").toString());

        GuardProperties guardProps = new GuardProperties();
        guardProps.getFileAccess().setAllowedRoots(List.of(base.toString()));
        pathGuard = new PathGuard(guardProps);

        storage = new MediaStorageGuard(props, pathGuard);
        storage.init();
        cache = new ImageCache(storage, props, pathGuard);
        cache.init();
    }

    @Test
    @DisplayName("存进去能按 MD5 取出来")
    void storeThenLookup() throws IOException {
        cache.store(KEY, "image/jpeg", BYTES);

        Optional<ImageCache.Cached> hit = cache.lookup(KEY);

        assertThat(hit).isPresent();
        assertThat(hit.get().mimeType()).isEqualTo("image/jpeg");
        assertThat(Files.readAllBytes(hit.get().path())).isEqualTo(BYTES);
    }

    @Test
    @DisplayName("★ 命中缓存时完全不下载（URL 指向不可达的内网地址也能返回）")
    void cacheHitSkipsDownloadEntirely() {
        cache.store(KEY, "image/jpeg", BYTES);
        ImageFetcher fetcher = new ImageFetcher(cache);

        // 这个 URL 既不可达、又会被 SSRF 防护拒绝。
        // 如果还走网络就必然失败 —— 能拿到图就证明走的是缓存。
        ImageRef ref = new ImageRef(KEY, "http://127.0.0.1:1/never.jpg", BYTES.length);

        Optional<ImageFetcher.FetchedImage> out = fetcher.fetch(ref);

        assertThat(out).isPresent();
        assertThat(Base64.getDecoder().decode(out.get().base64())).isEqualTo(BYTES);
    }

    @Test
    @DisplayName("没有缓存键时退回下载（该失败就失败，不会假装成功）")
    void noKeyFallsBackToDownload() {
        ImageFetcher fetcher = new ImageFetcher(cache);

        Optional<ImageFetcher.FetchedImage> out =
                fetcher.fetch(ImageRef.ofUrl("http://127.0.0.1:1/never.jpg"));

        assertThat(out).isEmpty();
    }

    @Test
    @DisplayName("命中会把「最后使用时间」刷成现在（清理就是按它算的）")
    void hitRefreshesLastUsedTime() throws IOException {
        cache.store(KEY, "image/jpeg", BYTES);
        Path file = cache.lookup(KEY).orElseThrow().path();

        long old = System.currentTimeMillis() - 48 * 3600_000L;
        Files.setLastModifiedTime(file, FileTime.fromMillis(old));

        cache.lookup(KEY);

        long now = System.currentTimeMillis();
        assertThat(Files.getLastModifiedTime(file).toMillis()).isBetween(now - 60_000, now + 60_000);
    }

    @Test
    @DisplayName("超出总量上限时按最后使用时间淘汰最旧的（LRU）")
    void evictsLeastRecentlyUsedWhenOverCapacity() throws IOException {
        byte[] halfMeg = new byte[500 * 1024];
        String[] keys = {
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
                "cccccccccccccccccccccccccccccccc",
        };
        for (String k : keys) {
            cache.store(k, "image/jpeg", halfMeg);
        }
        // 人为把 a 设成最旧、c 设成最新，让淘汰顺序可断言
        long now = System.currentTimeMillis();
        Files.setLastModifiedTime(storage.inboundDir().resolve(keys[0] + ".jpg"), FileTime.fromMillis(now - 3000));
        Files.setLastModifiedTime(storage.inboundDir().resolve(keys[1] + ".jpg"), FileTime.fromMillis(now - 2000));
        Files.setLastModifiedTime(storage.inboundDir().resolve(keys[2] + ".jpg"), FileTime.fromMillis(now - 1000));

        props.getTempImages().setMaxTotalSizeMb(1);   // 1MB 上限，现在有 1.5MB
        int removed = cache.enforceCapacity();

        assertThat(removed).isGreaterThanOrEqualTo(1);
        assertThat(Files.exists(storage.inboundDir().resolve(keys[0] + ".jpg"))).isFalse();
        assertThat(Files.exists(storage.inboundDir().resolve(keys[2] + ".jpg"))).isTrue();
    }

    @Test
    @DisplayName("键不合法（含路径穿越）时既不落盘也不命中")
    void rejectsUnsafeKeys() throws IOException {
        cache.store("../../etc/passwd", "image/jpeg", BYTES);
        cache.store("not-a-md5", "image/jpeg", BYTES);
        cache.store(null, "image/jpeg", BYTES);

        try (Stream<Path> files = Files.list(storage.inboundDir())) {
            assertThat(files).isEmpty();
        }
        assertThat(cache.lookup("../x")).isEmpty();
        assertThat(cache.lookup(KEY)).isEmpty();
    }

    @Test
    @DisplayName("写盘是原子的：不会留下 .part 半截文件")
    void leavesNoPartFiles() throws IOException {
        cache.store(KEY, "image/jpeg", BYTES);

        try (Stream<Path> files = Files.list(storage.inboundDir())) {
            assertThat(files.noneMatch(p -> p.getFileName().toString().endsWith(".part"))).isTrue();
        }
    }

    @Test
    @DisplayName("enabled=false 时完全不缓存")
    void disabledDoesNotCache() throws IOException {
        props.getTempImages().setEnabled(false);

        cache.store(KEY, "image/jpeg", BYTES);

        assertThat(cache.lookup(KEY)).isEmpty();
        try (Stream<Path> files = Files.list(storage.inboundDir())) {
            assertThat(files).isEmpty();
        }
    }
}
