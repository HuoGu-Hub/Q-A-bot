package com.example.qqbot.kb.map;

import com.example.qqbot.kb.MapOptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 底图缓存预热的**档位**与**缺口报告**。
 *
 * <h2>这里要守住的两件事</h2>
 * <ol>
 *   <li><b>档位必须和查看器真正会请求的一致</b>：白名单里的 {@code 1280} 前端永远不会请求
 *       （它在 {@code want >= 1280} 时换成 0 = 原图）。按配置循环去预热会白拉一百多张大图。</li>
 *   <li><b>缺口要如实上报</b>：旧实现把成功数当全部、状态硬编码为成功，定时任务跑失败
 *       没人会发现。现在 {@code missing > 0} 就必须是"失败"。</li>
 * </ol>
 *
 * <p>用假来源而不是真连 wiki：这里要证明的是"问了哪些档位、缺口怎么算"，
 * 连网只会让断言又慢又不稳。
 */
class MapJobsWarmTest {

    /** 记录每一次出站请求的假来源 —— "问了哪些档位"于是可被断言 */
    private static final class CountingSource implements MapImageSource {
        final List<String> asked = new CopyOnWriteArrayList<>();

        @Override
        public byte[] fetch(String fileName, int width) {
            asked.add(fileName + "@" + width);
            return ("bytes:" + fileName + ":" + width).getBytes();
        }

        @Override
        public String describe() {
            return "counting";
        }
    }

    /** 上游明确说"没有这个文件" */
    private static final class MissingSource implements MapImageSource {
        final List<String> asked = new CopyOnWriteArrayList<>();

        @Override
        public byte[] fetch(String fileName, int width) throws IOException {
            asked.add(fileName + "@" + width);
            throw new NotFound("上游没有这个文件（404）");
        }

        @Override
        public String describe() {
            return "missing";
        }
    }

    /** 一张地图：2 张瓦片 + 1 张底图 + 1 个分组图标，覆盖预热要遍历的三类资源 */
    private static MapViewService catalogOf() {
        List<MapViewService.TileView> tiles = List.of(
                new MapViewService.TileView("0_0.jpg", 0, 0),
                new MapViewService.TileView("0_1.jpg", 1, 0));
        MapViewService.CanvasView canvas = new MapViewService.CanvasView(
                "Map:Embervale/Background", "主地图",
                new double[]{0, 10240}, new double[]{10240, 0},
                new double[]{0, 0}, new double[]{1280, 1280},
                tiles, "bg.jpg", "#2e2e2c", 5, 8);
        MapViewService.GroupView group = new MapViewService.GroupView(
                "Enemy_Camp", "敌营", "Enemy Camp.png", false, false, "poi", 70);
        MapViewService.MapView map = new MapViewService.MapView(
                "Embervale", "余烬谷", 1064, 0, List.of(canvas), List.of(group), null);

        MapViewService view = mock(MapViewService.class);
        when(view.catalog()).thenReturn(List.of(map));
        return view;
    }

    private static MapOptions options(Path dir) {
        MapOptions o = new MapOptions();
        o.setTileDir(dir.toString());
        return o;
    }

    /** 只用到预热这一条路，同步与语料那两个协作者传 null */
    private static MapJobs jobs(MapImageCache cache, MapOptions options) {
        return new MapJobs(null, null, cache, catalogOf(), options);
    }

    @Test
    @DisplayName("★ 全量预热只请求查看器真正会用的档位：160/320/640/原图，绝不请求 1280")
    void fullWarmCoversViewerTiersOnly(@TempDir Path dir) {
        CountingSource source = new CountingSource();
        MapOptions options = options(dir);
        MapJobs jobs = jobs(new MapImageCache(options, source), options);

        MapJobs.WarmReport report = jobs.warm(MapJobs.FULL_WARM_WIDTHS);

        // 2 张瓦片 × 4 档 + 1 张底图 × 4 档 + 1 个图标（图标只有原图一档）
        assertThat(source.asked).containsExactlyInAnyOrder(
                "0_0.jpg@160", "0_0.jpg@320", "0_0.jpg@640", "0_0.jpg@0",
                "0_1.jpg@160", "0_1.jpg@320", "0_1.jpg@640", "0_1.jpg@0",
                "bg.jpg@160", "bg.jpg@320", "bg.jpg@640", "bg.jpg@0",
                "Enemy_Camp.png@0");
        // 显式再挡一次最容易踩的那个坑：配置白名单里有 1280，但那不是查看器的档位
        assertThat(source.asked).noneMatch(a -> a.endsWith("@1280"));

        assertThat(report.total()).isEqualTo(13);
        assertThat(report.fetched()).isEqualTo(13);
        assertThat(report.cached()).isZero();
        assertThat(report.missing()).isZero();
        assertThat(report.ok()).isTrue();
    }

    @Test
    @DisplayName("轻量预热不碰原图档：首屏要的 640 有，最贵的那一档没有")
    void liteWarmSkipsOriginalTier(@TempDir Path dir) {
        CountingSource source = new CountingSource();
        MapOptions options = options(dir);
        MapJobs jobs = jobs(new MapImageCache(options, source), options);

        jobs.warm(MapJobs.LITE_WARM_WIDTHS);

        assertThat(source.asked).containsExactlyInAnyOrder(
                "0_0.jpg@160", "0_0.jpg@320", "0_0.jpg@640",
                "0_1.jpg@160", "0_1.jpg@320", "0_1.jpg@640",
                "bg.jpg@160", "bg.jpg@320", "bg.jpg@640",
                "Enemy_Camp.png@0");
        // ★ 首屏细节层用的就是 640 —— 它必须在轻量档里，否则每次都由访客现取
        assertThat(source.asked).contains("0_0.jpg@640");
        assertThat(source.asked).doesNotContain("0_0.jpg@0", "0_1.jpg@0", "bg.jpg@0");
    }

    @Test
    @DisplayName("预热是幂等的：第二遍全部命中磁盘，一次出站都不发")
    void warmIsIdempotent(@TempDir Path dir) {
        CountingSource source = new CountingSource();
        MapOptions options = options(dir);
        MapJobs jobs = jobs(new MapImageCache(options, source), options);

        jobs.warm(MapJobs.FULL_WARM_WIDTHS);
        int firstRound = source.asked.size();
        MapJobs.WarmReport second = jobs.warm(MapJobs.FULL_WARM_WIDTHS);

        assertThat(second.fetched()).isZero();
        assertThat(second.cached()).isEqualTo(13);
        assertThat(second.ok()).isTrue();
        assertThat(source.asked).hasSize(firstRound);
    }

    @Test
    @DisplayName("★ 有缺口就不算成功：上游 404 会让 missing > 0、ok 为 false")
    void gapsAreReportedNotSwallowed(@TempDir Path dir) {
        MissingSource source = new MissingSource();
        MapOptions options = options(dir);
        MapJobs jobs = jobs(new MapImageCache(options, source), options);

        MapJobs.WarmReport report = jobs.warm(MapJobs.FULL_WARM_WIDTHS);

        assertThat(report.total()).isEqualTo(13);
        assertThat(report.fetched()).isZero();
        assertThat(report.cached()).isZero();
        assertThat(report.missing()).isEqualTo(13);
        assertThat(report.ok()).as("有缺口就必须是失败，不能显示成功").isFalse();
        // "文件不存在"按文件记、不按档位记：4 个文件只问上游 4 次，其余档位直接挡掉
        assertThat(source.asked).hasSize(4);
    }
}
