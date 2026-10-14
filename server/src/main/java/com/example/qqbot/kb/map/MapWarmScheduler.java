package com.example.qqbot.kb.map;

import com.example.qqbot.kb.MapOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 非高峰时段的**全量**底图预热。
 *
 * <h2>为什么要有定时这一份</h2>
 * 预热是幂等的：已经落盘的图直接命中磁盘，一个字节都不再请求上游。
 * 所以它真正的价值不是"每天刷一遍"，而是**内容变化后自动补齐** ——
 * 定时这一份就是那张兜底网：新地图、新瓦片、被清过的缓存，一觉醒来已经补好了。
 *
 * <h2>为什么必须是"非高峰"</h2>
 * 出站请求是**串行节流**的（{@code WikiMapImageSource} 的闸门与间隔都是全局唯一），
 * 全量要拉几百张、十几分钟。高峰期跑它会发生两件坏事：
 * 访客的取图请求要和预热抢同一把闸门；撞出的 429 会把出站间隔顶到上限，
 * 而那个间隔对访客同样生效 —— 他们看到的是地图缺块、慢慢补。
 *
 * <p>窗口是按实测选的：近 30 天 {@code qa_stat} 里，本地 03:00–06:00 只占全天约 0.5%
 * 的活跃（04:00–05:00 各只有 11 条），而峰值 18:00 有 10%。04:00 落在最静的一段。
 * 与 {@code QaCleaner}（04:30）不冲突：这个方法**立即返回**，活是丢给 {@code map-jobs} 线程的。
 *
 * <h2>⚠️ 不要在这里直接调 {@code warm()}</h2>
 * Spring 默认的调度线程池**只有一个线程**（本项目没有自定义 {@code TaskScheduler}）。
 * 在这里直接跑十几分钟的预热，会把 {@code QaCleaner} 和 {@code TempImageCleaner}
 * 一起堵住。必须经由 {@link MapJobs#startFullWarm()} 提交到地图自己的单线程池。
 *
 * <p>关掉它：把 {@code app.kb.map.warm-cron} 设为 {@code "-"}（Spring 的"停用"约定）。
 */
@Component
public class MapWarmScheduler {

    private static final Logger log = LoggerFactory.getLogger(MapWarmScheduler.class);

    private final MapJobs jobs;
    private final MapOptions options;

    public MapWarmScheduler(MapJobs jobs, MapOptions options) {
        this.jobs = jobs;
        this.options = options;
    }

    /**
     * cron 与 zone 从配置读，默认凌晨 4 点。
     *
     * <p>时区**必须写死**：容器里 JVM 的默认时区可能是 UTC，
     * 不写的话这个任务会跑到本地中午 —— 正好撞进流量峰值。
     */
    @Scheduled(cron = "${app.kb.map.warm-cron:0 0 4 * * *}",
            zone = "${app.kb.map.warm-zone:Asia/Shanghai}")
    public void fullWarmOffPeak() {
        if (!options.isEnabled()) {
            log.debug("[MAP-WARM] 地图功能已关闭，跳过非高峰全量预热");
            return;
        }
        if (!jobs.startFullWarm()) {
            log.info("[MAP-WARM] 已有预热在跑，本次非高峰全量预热跳过");
            return;
        }
        log.info("[MAP-WARM] 非高峰全量预热已排入队列（含原图档，首次约十几分钟）");
    }
}
