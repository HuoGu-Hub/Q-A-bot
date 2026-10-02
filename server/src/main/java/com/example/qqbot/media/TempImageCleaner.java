package com.example.qqbot.media;

import com.example.qqbot.guard.PathGuard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * 临时图片的定时清理。
 *
 * <p>NapCat 在「发一张来自 URL 的图片」时会把它下载到自己的 temp 目录且<b>不会自动删</b>，
 * 业务层以后做图片缓存/生成也会产生中间文件。这些都不该无限增长，所以按
 * <b>最后修改时间</b>定期清理。
 *
 * <p>几条硬性约束：
 * <ol>
 *   <li><b>只删 tmp 目录</b>：目录由 {@link MediaStorageGuard} 在启动时校验过，
 *       和知识库图片物理隔离，配错会直接拒绝启动。</li>
 *   <li><b>每个文件都过 {@link PathGuard}</b>：即使目录配对了，
 *       path-guard 的尾缀/片段黑名单仍然生效，作为第二道防线。</li>
 *   <li><b>单轮有上限</b>：{@code max-delete-per-run} 防止一次性打满磁盘 IO。</li>
 *   <li><b>可观测</b>：每轮都记录「候选多少、删了多少、释放多少空间、跳过/失败多少」。</li>
 * </ol>
 *
 * <p>第一次执行不设初始延迟 —— 进程启动后立刻清一轮，
 * 这样"上次崩溃前留下的过期文件"不会多活一个小时。
 */
@Component
public class TempImageCleaner {

    private static final Logger log = LoggerFactory.getLogger(TempImageCleaner.class);

    private final MediaPolicy props;
    private final MediaStorageGuard storage;
    private final PathGuard pathGuard;
    private final ImageCache imageCache;

    public TempImageCleaner(MediaPolicy props, MediaStorageGuard storage, PathGuard pathGuard,
                            ImageCache imageCache) {
        this.props = props;
        this.storage = storage;
        this.pathGuard = pathGuard;
        this.imageCache = imageCache;
    }

    /**
     * 一轮清理的结果。
     *
     * @param candidates  过期（超过保留时长）的文件总数
     * @param deleted     实际删除数
     * @param skipped     被 PathGuard 拦下、未删除的数量
     * @param failed      删除时报错的数量
     * @param freedBytes  实际释放的字节数
     * @param dirsRemoved 顺带清掉的空目录数
     * @param evicted     因为超出缓存上限而被 LRU 淘汰的文件数
     */
    public record CleanupReport(int candidates, int deleted, int skipped, int failed,
                                long freedBytes, int dirsRemoved, int evicted) {

        public static CleanupReport empty() {
            return new CleanupReport(0, 0, 0, 0, 0, 0, 0);
        }
    }

    /** 定时入口。任何异常都不能让调度线程死掉，所以这里兜底 catch。 */
    @Scheduled(fixedDelayString = "${app.media.temp-images.cleanup-interval-minutes:60}",
            timeUnit = TimeUnit.MINUTES)
    public void scheduledCleanup() {
        try {
            cleanup();
        } catch (Exception e) {
            log.error("[MEDIA] 临时图片清理异常：{}", e.getMessage(), e);
        }
    }

    /** 执行一轮清理。返回本轮统计，供日志/测试/将来的指标使用。 */
    public CleanupReport cleanup() {
        TempImages cfg = props.getTempImages();
        if (!cfg.isEnabled()) {
            log.debug("[MEDIA] 临时图片清理已关闭，跳过");
            return CleanupReport.empty();
        }

        Path root = storage.tmpDir();
        if (!Files.isDirectory(root)) {
            log.warn("[MEDIA] 临时图片目录不存在，本轮跳过：{}", root);
            return CleanupReport.empty();
        }

        long cutoff = System.currentTimeMillis() - cfg.getRetentionHours() * 3600_000L;

        List<Path> candidates = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(root)) {
            walk.filter(Files::isRegularFile)
                    .filter(p -> lastModified(p) < cutoff)
                    .forEach(candidates::add);
        } catch (IOException e) {
            log.error("[MEDIA] 遍历临时图片目录失败：{} —— {}", root, e.getMessage());
            return CleanupReport.empty();
        }

        // 先删最旧的：万一撞上单轮上限，留下的也是相对新的文件
        candidates.sort(Comparator.comparingLong(TempImageCleaner::lastModified));

        int deleted = 0;
        int skipped = 0;
        int failed = 0;
        long freed = 0;
        for (Path file : candidates) {
            if (deleted >= cfg.getMaxDeletePerRun()) {
                log.warn("[MEDIA] 已达单轮删除上限 {} 个，剩余 {} 个留到下一轮",
                        cfg.getMaxDeletePerRun(), candidates.size() - deleted);
                break;
            }

            PathGuard.Decision decision = pathGuard.check(file.toString());
            if (!decision.allowed()) {
                skipped++;
                log.warn("[MEDIA] 清理被 PathGuard 拦下，未删除：{} —— {}", file, decision.reason());
                continue;
            }

            try {
                long size = Files.size(file);
                Files.delete(file);
                deleted++;
                freed += size;
            } catch (IOException e) {
                failed++;
                log.warn("[MEDIA] 删除失败：{} —— {}", file, e.getMessage());
            }
        }

        int dirsRemoved = removeEmptyDirs(root);

        // 再按「最后使用时间」把总量压回上限以内（LRU）。
        // 光有 TTL 不够：热门群可能一小时内就塞进几百兆，保留时长还没到磁盘就先满了。
        int evicted = imageCache.enforceCapacity();

        if (deleted == 0 && skipped == 0 && failed == 0 && evicted == 0) {
            log.info("[MEDIA] 临时图片清理：没有过期文件（保留 {} 小时，目录 {}）",
                    cfg.getRetentionHours(), root);
        } else {
            log.info("[MEDIA] 临时图片清理：候选 {} 个，删除 {} 个，跳过 {} 个，失败 {} 个，"
                            + "释放 {}，清理空目录 {} 个，超限淘汰 {} 个（保留 {} 小时，目录 {}）",
                    candidates.size(), deleted, skipped, failed, ImageCache.humanSize(freed),
                    dirsRemoved, evicted, cfg.getRetentionHours(), root);
        }
        return new CleanupReport(candidates.size(), deleted, skipped, failed, freed, dirsRemoved, evicted);
    }

    /** 清掉空目录（不含根目录本身）。删不掉（非空/被占用）就跳过，不是错误。 */
    private int removeEmptyDirs(Path root) {
        List<Path> dirs = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(root)) {
            walk.filter(Files::isDirectory)
                    .filter(p -> !p.equals(root))
                    .forEach(dirs::add);
        } catch (IOException e) {
            return 0;
        }
        // 由深到浅，子目录删掉后父目录才可能变空
        dirs.sort(Comparator.comparingInt((Path p) -> p.getNameCount()).reversed());

        int removed = 0;
        for (Path dir : dirs) {
            if (!pathGuard.check(dir.toString()).allowed()) {
                continue;
            }
            try (Stream<Path> children = Files.list(dir)) {
                if (children.findAny().isPresent()) {
                    continue;
                }
            } catch (IOException e) {
                continue;
            }
            try {
                Files.delete(dir);
                removed++;
            } catch (IOException ignored) {
                // 非空/被占用：下一轮再看
            }
        }
        return removed;
    }

    private static long lastModified(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (IOException e) {
            // 读不到时间就当成"刚刚修改"，宁可少删也不能误删
            return Long.MAX_VALUE;
        }
    }

}
