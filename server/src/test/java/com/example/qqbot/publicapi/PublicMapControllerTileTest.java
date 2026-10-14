package com.example.qqbot.publicapi;

import com.example.qqbot.kb.MapOptions;
import com.example.qqbot.kb.PlaceDirectory;
import com.example.qqbot.kb.map.MapImageCache;
import com.example.qqbot.kb.map.MapImageSource;
import com.example.qqbot.kb.map.MapImageWorker;
import com.example.qqbot.kb.map.MapViewService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.ResponseEntity;
import org.springframework.web.context.request.async.DeferredResult;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 瓦片接口的**线程归属**与**过载行为**。
 *
 * <h2>这里守的是什么</h2>
 * 2026-10-14 的故障：一次打开地图就是几十个瓦片请求，而取图是同步等上游的，
 * 于是 Tomcat 的 100 个线程被瓦片吃光，而群消息入口 {@code /onebot/event}
 * 走的是同一个线程池 —— 机器人表现为"@ 不回，/ping 也没反应"。
 *
 * <p>所以本测试要证明两件事：
 * <ol>
 *   <li><b>取图不在调用方线程上跑</b>：调用 {@code tile()} 的那条线程（线上就是 Tomcat 线程）
 *       立刻拿回控制权，真正的取图发生在 {@code map-img} 线程上；</li>
 *   <li><b>队列满了要快速失败</b>：回 503 + Retry-After，而不是把请求无限挂住。</li>
 * </ol>
 *
 * <p>不起 Spring MVC：这里验的是控制器的编排，不是 HTTP 编解码。
 */
class PublicMapControllerTileTest {

    /** 记录每次取图发生在哪条线程上 */
    private static final class ThreadRecordingSource implements MapImageSource {
        final List<String> threads = new CopyOnWriteArrayList<>();
        final List<String> asked = new CopyOnWriteArrayList<>();

        @Override
        public byte[] fetch(String fileName, int width) {
            threads.add(Thread.currentThread().getName());
            asked.add(fileName + "@" + width);
            return ("bytes:" + fileName + ":" + width).getBytes();
        }

        @Override
        public String describe() {
            return "thread-recording";
        }
    }

    private MapViewService view;
    private PlaceDirectory directory;
    private PublicRateLimiter limiter;

    @BeforeEach
    void setUp() {
        view = mock(MapViewService.class);
        directory = mock(PlaceDirectory.class);
        limiter = mock(PublicRateLimiter.class);
    }

    private PublicMapController controller(MapImageCache cache, MapImageWorker worker) {
        return new PublicMapController(view, cache, new MapOptions(), directory, limiter, worker);
    }

    /** DeferredResult 没有阻塞的 get，只能轮询 —— 顺便证明它确实是"稍后才有结果" */
    @SuppressWarnings("unchecked")
    private static ResponseEntity<byte[]> await(DeferredResult<ResponseEntity<byte[]>> pending,
                                                long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (!pending.hasResult() && System.currentTimeMillis() < deadline) {
            Thread.sleep(5);
        }
        assertThat(pending.hasResult()).as("DeferredResult 在 %d ms 内没有结果", timeoutMs).isTrue();
        return (ResponseEntity<byte[]>) pending.getResult();
    }

    @Test
    @DisplayName("★ 取图跑在 map-img 线程上，调用方（线上是 Tomcat 线程）立刻拿回控制权")
    void tileIsFetchedOffTheCallerThread(@TempDir Path dir) throws Exception {
        ThreadRecordingSource source = new ThreadRecordingSource();
        MapOptions options = new MapOptions();
        options.setTileDir(dir.toString());
        MapImageCache cache = new MapImageCache(options, source);
        when(view.allowedAsset("Embervale", "Map:Embervale/Background", "0_0.jpg")).thenReturn(true);

        String caller = Thread.currentThread().getName();
        DeferredResult<ResponseEntity<byte[]>> pending = controller(cache, new MapImageWorker())
                .tile("Embervale", "Map:Embervale/Background", "0_0.jpg", "640");

        ResponseEntity<byte[]> res = await(pending, 5_000);

        assertThat(res.getStatusCode().value()).isEqualTo(200);
        assertThat(res.getBody()).isEqualTo("bytes:0_0.jpg:640".getBytes());
        assertThat(source.asked).containsExactly("0_0.jpg@640");
        // ★ 关键断言：取图没有发生在调用方线程上
        assertThat(source.threads).isNotEmpty().allMatch(t -> t.startsWith("map-img"));
        assertThat(source.threads).as("调用方线程不能被取图占住").doesNotContain(caller);
    }

    @Test
    @DisplayName("★ 取图队列满：回 503 + Retry-After，且不碰缓存、不挂住请求")
    void saturatedQueueFailsFast() throws Exception {
        // 把默认池彻底灌满：所有线程与排队位都被"永不返回"的任务占住。
        // 灌满后状态不会自己恢复，所以"下一个必然被拒"是确定的，不靠时序。
        MapImageWorker worker = new MapImageWorker();
        CountDownLatch release = new CountDownLatch(1);
        int accepted = 0;
        while (worker.submit(() -> block(release))) {
            accepted++;
            assertThat(accepted).as("池必须有界 —— 无限接受正是这次要修的问题")
                    .isLessThan(100_000);
        }
        assertThat(accepted).as("默认池至少该接受几个任务").isGreaterThan(0);

        MapImageCache cache = mock(MapImageCache.class);
        when(view.allowedAsset(any(), any(), any())).thenReturn(true);

        try {
            ResponseEntity<byte[]> res = await(
                    controller(cache, worker).tile("Embervale", "Map:Embervale/Background", "0_0.jpg", "640"),
                    2_000);

            assertThat(res.getStatusCode().value()).isEqualTo(503);
            assertThat(res.getHeaders().getFirst("Retry-After")).isEqualTo("2");
            verify(cache, never()).get(any(), any(), any(), anyInt());
        } finally {
            release.countDown();
        }
    }

    @Test
    @DisplayName("白名单没过的资源直接 404，不占用取图线程")
    void assetOutsideWhitelistIsRejectedImmediately() {
        when(view.allowedAsset(any(), any(), any())).thenReturn(false);
        MapImageCache cache = mock(MapImageCache.class);
        MapImageWorker worker = new MapImageWorker();

        DeferredResult<ResponseEntity<byte[]>> pending = controller(cache, worker)
                .tile("Embervale", "Map:Embervale/Background", "../secret.jpg", null);

        assertThat(pending.hasResult()).as("不必等异步派发").isTrue();
        assertThat(((ResponseEntity<?>) pending.getResult()).getStatusCode().value()).isEqualTo(404);
        assertThat(worker.queued()).as("不该进队列").isZero();
        verify(cache, never()).get(any(), any(), any(), anyInt());
    }

    @Test
    @DisplayName("缓存未命中回 404，而不是 500")
    void missIsNotFound() throws Exception {
        when(view.allowedAsset(any(), any(), any())).thenReturn(true);
        MapImageCache missing = mock(MapImageCache.class);
        when(missing.get(any(), any(), any(), anyInt())).thenReturn(Optional.empty());

        ResponseEntity<byte[]> res = await(controller(missing, new MapImageWorker())
                .tile("Embervale", "Map:Embervale/Background", "nope.jpg", null), 5_000);

        assertThat(res.getStatusCode().value()).isEqualTo(404);
    }

    private static void block(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
