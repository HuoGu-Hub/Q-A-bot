package com.example.qqbot.kb.map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 按需预热的验收测试。
 *
 * <p>要证明两件事：**缺的瓦片真的会被取一遍**，以及**同一个缺口不会被反复排队**
 * —— 群里连着问同一个点位是常态，重复排队只会白吃 wiki 的限流额度。
 */
class MapShotWarmerTest {

    private static final String MAP = "Embervale";
    private static final String PAGE = "Map:Embervale/Background";

    @Test
    @DisplayName("★ 缺的瓦片会被后台真的取一遍")
    void fetchesMissingTiles() {
        MapImageCache images = mock(MapImageCache.class);
        when(images.get(any(), any(), any(), anyInt())).thenReturn(Optional.empty());
        MapShotWarmer warmer = new MapShotWarmer(images);

        warmer.warmAsync(MAP, PAGE, List.of("3_2.jpg", "4_3.jpg"), 640);

        verify(images, timeout(3000).times(1)).get(MAP, PAGE, "3_2.jpg", 640);
        verify(images, timeout(3000).times(1)).get(MAP, PAGE, "4_3.jpg", 640);
    }

    @Test
    @DisplayName("★ 同一个缺口连着问几次只排一次队")
    void dedupesQueuedTiles() throws Exception {
        CountDownLatch gate = new CountDownLatch(1);
        MapImageCache images = mock(MapImageCache.class);
        when(images.get(any(), any(), any(), anyInt())).thenAnswer(inv -> {
            gate.await(3, TimeUnit.SECONDS);
            return Optional.empty();
        });
        MapShotWarmer warmer = new MapShotWarmer(images);

        // 第一批还在跑的时候再排一次同一个缺口 —— 应当被去重挡掉
        warmer.warmAsync(MAP, PAGE, List.of("a.jpg"), 640);
        warmer.warmAsync(MAP, PAGE, List.of("a.jpg"), 640);
        gate.countDown();
        Thread.sleep(300);

        verify(images, times(1)).get(any(), any(), eq("a.jpg"), anyInt());
    }

    @Test
    @DisplayName("空清单什么都不做，也不报错")
    void emptyListIsNoop() {
        MapImageCache images = mock(MapImageCache.class);
        MapShotWarmer warmer = new MapShotWarmer(images);

        assertThatCode(() -> {
            warmer.warmAsync(MAP, PAGE, List.of(), 640);
            warmer.warmAsync(MAP, PAGE, null, 640);
            warmer.warmAsync(null, PAGE, List.of("a.jpg"), 640);
        }).doesNotThrowAnyException();

        verify(images, times(0)).get(any(), any(), any(), anyInt());
    }
}
