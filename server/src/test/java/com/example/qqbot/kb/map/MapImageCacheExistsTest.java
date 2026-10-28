package com.example.qqbot.kb.map;

import com.example.qqbot.kb.MapOptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link MapImageCache#exists} 的验收：回答「有没有」时**不读字节、不碰上游**。
 *
 * <p>状态页的预热覆盖要逐档问几百个格子。用 {@code cached()} 时每次都把整张图读进内存，
 * 实测一次状态请求白读 125 MB；这里换成只做一次 {@code isRegularFile}。
 */
class MapImageCacheExistsTest {

    private static final class CountingSource implements MapImageSource {
        final List<String> asked = new CopyOnWriteArrayList<>();

        @Override
        public byte[] fetch(String fileName, int width) {
            asked.add(fileName + "@" + width);
            return ("bytes:" + fileName).getBytes();
        }

        @Override
        public String describe() {
            return "counting";
        }
    }

    private static MapImageCache cache(Path dir, CountingSource source) {
        MapOptions options = new MapOptions();
        options.setTileDir(dir.toString());
        return new MapImageCache(options, source);
    }

    @Test
    void existsIsFalseUntilWarmedAndNeverAsksUpstream(@TempDir Path dir) {
        CountingSource source = new CountingSource();
        MapImageCache cache = cache(dir, source);

        assertThat(cache.exists("Embervale", null, "Village.png", 0)).isFalse();
        assertThat(source.asked).as("只是问有没有，不许碰上游").isEmpty();

        cache.get("Embervale", null, "Village.png", 0);

        assertThat(cache.exists("Embervale", null, "Village.png", 0)).isTrue();
        assertThat(source.asked).as("预热之后也不该再问一次").hasSize(1);
    }

    /** 投递位必须是**文件**：同名目录不算命中，否则覆盖统计会虚高 */
    @Test
    void existsRejectsDirectories(@TempDir Path dir) throws Exception {
        MapImageCache cache = cache(dir, new CountingSource());

        Path target = cache.root().resolve("Embervale").resolve("Village.png");
        Files.createDirectories(target);

        assertThat(cache.exists("Embervale", null, "Village.png", 0)).isFalse();
    }

    @Test
    void existsRejectsSuspiciousNames(@TempDir Path dir) {
        CountingSource source = new CountingSource();
        MapImageCache cache = cache(dir, source);

        assertThat(cache.exists("Embervale", null, "../.env", 0)).isFalse();
        assertThat(source.asked).isEmpty();
    }
}
