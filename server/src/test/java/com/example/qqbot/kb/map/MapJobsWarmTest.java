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
 *   <li><b>档位必须和真正会请求它的人一致</b>：查看器在 {@code want >= 1280} 时换成 0 = 原图，
 *       所以它不请求 1280；<b>但地图出图会</b>（输出 1280、最小跨度 1200 → 选中 1280 档）。
 *       1280 曾经因此被排除在预热之外，后果是出图永远拿不到预热好的瓦片。</li>
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
        // mapStore 只有"清空地图数据"才用得到，这个测试不碰它
        return new MapJobs(null, null, cache, catalogOf(), options, null);
    }

    @Test
    @DisplayName("★ 全量预热覆盖查看器与出图会用到的全部档位：160/320/640/1280/原图")
    void fullWarmCoversViewerAndShotTiers(@TempDir Path dir) {
        CountingSource source = new CountingSource();
        MapOptions options = options(dir);
        MapJobs jobs = jobs(new MapImageCache(options, source), options);

        MapJobs.WarmReport report = jobs.warm(MapJobs.FULL_WARM_WIDTHS);

        // 2 张瓦片 × 5 档 + 1 张底图 × 5 档 + 1 个图标（图标只有原图一档）
        assertThat(source.asked).containsExactlyInAnyOrder(
                "0_0.jpg@160", "0_0.jpg@320", "0_0.jpg@640", "0_0.jpg@1280", "0_0.jpg@0",
                "0_1.jpg@160", "0_1.jpg@320", "0_1.jpg@640", "0_1.jpg@1280", "0_1.jpg@0",
                "bg.jpg@160", "bg.jpg@320", "bg.jpg@640", "bg.jpg@1280", "bg.jpg@0",
                "Enemy_Camp.png@0");
        // ★ 出图选的就是 1280 档（见 MapShotRenderer.pickTileWidth）：不预热它，
        //   每个新点位第一次问必然没图 —— 这条断言就是把那个回归钉住
        assertThat(source.asked).contains("0_0.jpg@1280", "0_1.jpg@1280", "bg.jpg@1280");

        assertThat(report.total()).isEqualTo(16);
        assertThat(report.fetched()).isEqualTo(16);
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
        assertThat(second.cached()).isEqualTo(16);
        assertThat(second.ok()).isTrue();
        assertThat(source.asked).hasSize(firstRound);
    }

    @Test
    @DisplayName("时长说人话：摘要里不再出现 1952091 ms 这种要现场换算的数字")
    void durationIsHuman() {
        assertThat(MapJobs.humanMs(850)).isEqualTo("850 ms");
        assertThat(MapJobs.humanMs(18_400)).isEqualTo("18 秒");
        assertThat(MapJobs.humanMs(120_000)).isEqualTo("2 分");
        assertThat(MapJobs.humanMs(1_952_091)).isEqualTo("32 分 32 秒");
        assertThat(MapJobs.humanMs(5_100_000)).isEqualTo("1 小时 25 分");
    }

    @Test
    @DisplayName("★ 从未运行过的任务是「未运行」，不是「成功」—— 只看 ok 就会把没跑过显示成成功")
    void idleIsNotSuccess(@TempDir Path dir) {
        CountingSource source = new CountingSource();
        MapOptions options = options(dir);
        MapJobs jobs = jobs(new MapImageCache(options, source), options);

        MapJobs.JobState warm = jobs.states().get(MapJobs.WARM);
        assertThat(warm.running()).isFalse();
        assertThat(warm.finishedAt()).as("没跑过就没有结束时间 —— 界面靠它区分未运行与失败").isEmpty();
        assertThat(warm.ok()).as("没跑过不是成功").isFalse();
    }

    @Test
    @DisplayName("★ 预热要报进度：总数开工前就定下来，每一项报一次，最后 done == total")
    void warmReportsProgress(@TempDir Path dir) {
        CountingSource source = new CountingSource();
        MapOptions options = options(dir);
        MapJobs jobs = jobs(new MapImageCache(options, source), options);

        List<String> ticks = new CopyOnWriteArrayList<>();
        MapJobs.WarmReport report = jobs.warm(MapJobs.FULL_WARM_WIDTHS,
                (done, total) -> ticks.add(done + "/" + total));

        assertThat(report.total()).isEqualTo(16);
        // 几十分钟的任务不能是黑箱：总数从第一条就固定，进度只增不减
        assertThat(ticks).hasSize(16).allMatch(t -> t.endsWith("/16"));
        assertThat(ticks.get(0)).isEqualTo("1/16");
        assertThat(ticks.get(15)).isEqualTo("16/16");
    }

    @Test
    @DisplayName("★ 有缺口就不算成功：上游 404 会让 missing > 0、ok 为 false")
    void gapsAreReportedNotSwallowed(@TempDir Path dir) {
        MissingSource source = new MissingSource();
        MapOptions options = options(dir);
        MapJobs jobs = jobs(new MapImageCache(options, source), options);

        MapJobs.WarmReport report = jobs.warm(MapJobs.FULL_WARM_WIDTHS);

        assertThat(report.total()).isEqualTo(16);
        assertThat(report.fetched()).isZero();
        assertThat(report.cached()).isZero();
        assertThat(report.missing()).isEqualTo(16);
        assertThat(report.ok()).as("有缺口就必须是失败，不能显示成功").isFalse();
        // "文件不存在"按文件记、不按档位记：4 个文件只问上游 4 次，其余档位直接挡掉
        assertThat(source.asked).hasSize(4);
    }
}
