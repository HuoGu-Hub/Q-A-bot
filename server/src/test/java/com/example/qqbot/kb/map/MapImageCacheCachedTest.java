package com.example.qqbot.kb.map;

import com.example.qqbot.kb.MapOptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link MapImageCache#cached} 的验收：**只看缓存，一次出站都不发**。
 *
 * <p>地图出图要一口气画十几个分组图标。图标没预热到的时候，宁可这张图少一个图标，
 * 也不能让群友在回复路径上等上游 —— 出站是全局串行节流的，一张就是好几秒。
 */
class MapImageCacheCachedTest {

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
    void cachedNeverAsksUpstream(@TempDir Path dir) {
        CountingSource source = new CountingSource();
        MapImageCache cache = cache(dir, source);

        assertThat(cache.cached("Embervale", null, "Village.png", 0)).isEmpty();
        assertThat(source.asked).as("缓存里没有也不许碰上游").isEmpty();

        // 预热过之后才读得到
        cache.get("Embervale", null, "Village.png", 0);
        assertThat(cache.cached("Embervale", null, "Village.png", 0)).isPresent();
        assertThat(source.asked).hasSize(1);
    }

    @Test
    void cachedRejectsSuspiciousNames(@TempDir Path dir) {
        CountingSource source = new CountingSource();
        MapImageCache cache = cache(dir, source);

        assertThat(cache.cached("Embervale", null, "../.env", 0)).isEmpty();
        assertThat(source.asked).isEmpty();
    }
}
