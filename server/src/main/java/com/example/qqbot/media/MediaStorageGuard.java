package com.example.qqbot.media;

import com.example.qqbot.config.MediaProperties;
import com.example.qqbot.guard.PathGuard;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 媒体目录的「物理隔离」守卫 —— 启动时校验目录配置，<b>配错就拒绝启动</b>。
 *
 * <p>它解决的是一个后果极其严重、但成因极蠢的问题：
 * 如果 {@code app.media.temp-images.dir} 不小心被配成了 {@code kb-images} 的路径，
 * 定时清理会把<b>知识库图片当成过期垃圾全部删掉</b>，而且不可恢复。
 * 靠"写配置时小心一点"是防不住的 —— 所以让程序在启动阶段就崩掉。
 *
 * <p>校验规则（任何一条不过 → 抛异常，Spring 启动失败）：
 * <ol>
 *   <li>临时目录与知识库目录不能是<b>同一个</b>路径</li>
 *   <li>知识库目录不能<b>位于临时目录之内</b>（递归清理会连它一起删）</li>
 *   <li>临时目录必须能通过 {@link PathGuard} 的检查（否则清理会全部被拦下、静默失效）</li>
 *   <li>保留时长 / 清理间隔 / 单次上限必须是正数</li>
 * </ol>
 *
 * <p>顺带把两个目录建出来，并把<b>解析后的绝对路径</b>打进日志 ——
 * 相对路径到底落在哪个盘，是排查"清理为什么没生效"时最先要看的东西。
 */
@Component
public class MediaStorageGuard {

    private static final Logger log = LoggerFactory.getLogger(MediaStorageGuard.class);

    /** 缓存目录名：用户发来的图片（inbound）。生成/转发产物以后放 outbound */
    public static final String INBOUND_DIR_NAME = "inbound";

    private final MediaProperties props;
    private final PathGuard pathGuard;

    private Path tmpDir;
    private Path kbDir;

    public MediaStorageGuard(MediaProperties props, PathGuard pathGuard) {
        this.props = props;
        this.pathGuard = pathGuard;
    }

    @PostConstruct
    public void init() {
        if (props.getMaxImagesPerMessage() < 0) {
            throw new IllegalStateException("[MEDIA] 配置错误：app.media.max-images-per-message 不能为负数，"
                    + "0 表示不限制");
        }

        Path tmp = resolve("app.media.temp-images.dir", props.getTempImages().getDir());
        Path kb = resolve("app.media.kb-images.dir", props.getKbImages().getDir());

        // ① 绝不能是同一个目录
        if (tmp.equals(kb)) {
            throw new IllegalStateException(String.format("""
                    [MEDIA] 配置错误：临时图片目录和知识库图片目录是同一个路径！
                      临时图片：%s
                      知识库图片：%s
                    这会让定时清理把知识库图片当成过期文件删掉。
                    请把 app.media.temp-images.dir 和 app.media.kb-images.dir 配成两个不同的目录。""",
                    tmp, kb));
        }

        // ② 知识库不能落在临时目录里面（临时目录是递归清理的）
        if (kb.startsWith(tmp)) {
            throw new IllegalStateException(String.format("""
                    [MEDIA] 配置错误：知识库图片目录位于临时图片目录内部！
                      临时图片（会被递归清理）：%s
                      知识库图片：%s
                    请把知识库挪到临时目录之外。""", tmp, kb));
        }

        // ③ 临时目录反过来落在知识库里：不致命，但说明目录规划很乱，提醒一下
        if (tmp.startsWith(kb)) {
            log.warn("[MEDIA] 临时图片目录位于知识库目录内部（{} 在 {} 之内）。"
                    + "不影响安全，但建议把两者分开放，边界更清晰。", tmp, kb);
        }

        boolean tempEnabled = props.getTempImages().isEnabled();
        if (tempEnabled) {
            validatePositive("app.media.temp-images.retention-hours", props.getTempImages().getRetentionHours());
            validatePositive("app.media.temp-images.cleanup-interval-minutes",
                    props.getTempImages().getCleanupIntervalMinutes());
            validatePositive("app.media.temp-images.max-delete-per-run", props.getTempImages().getMaxDeletePerRun());
            if (props.getTempImages().getMaxTotalSizeMb() < 0) {
                throw new IllegalStateException("[MEDIA] 配置错误：app.media.temp-images.max-total-size-mb 不能为负数，"
                        + "0 表示不限制");
            }

            ensureDirectory(tmp, "临时图片目录");
            // 缓存目录固定叫 inbound，和将来的 outbound（生成/转发的中间产物）分开
            ensureDirectory(tmp.resolve(INBOUND_DIR_NAME), "图片缓存目录");

            // ④ 必须能被 PathGuard 放行，否则清理会 100% 被拦下，看起来"跑了但什么都没删"
            PathGuard.Decision decision = pathGuard.check(tmp.toString());
            if (!decision.allowed()) {
                throw new IllegalStateException(String.format("""
                        [MEDIA] 配置错误：临时图片目录不在 PathGuard 的允许范围内，清理任务会被全部拦下。
                          目录：%s
                          原因：%s
                        请把该目录加入 app.guard.file-access.allowed-roots（留空时只允许程序工作目录）。""",
                        tmp, decision.reason()));
            }
        }

        ensureDirectory(kb, "知识库图片目录");

        this.tmpDir = tmp;
        this.kbDir = kb;

        log.info("[MEDIA] 目录隔离已生效：临时={}  知识库={}", tmp, kb);
        log.info("[MEDIA] 单条消息图片上限：{}",
                props.getMaxImagesPerMessage() > 0 ? props.getMaxImagesPerMessage() + " 张" : "不限制");
        if (tempEnabled) {
            log.info("[MEDIA] 临时图片清理：每 {} 分钟跑一次，保留 {} 小时，单次最多删 {} 个，缓存上限 {}",
                    props.getTempImages().getCleanupIntervalMinutes(),
                    props.getTempImages().getRetentionHours(),
                    props.getTempImages().getMaxDeletePerRun(),
                    props.getTempImages().getMaxTotalSizeMb() > 0
                            ? props.getTempImages().getMaxTotalSizeMb() + " MB"
                            : "不限制");
        } else {
            log.info("[MEDIA] 临时图片清理已关闭（app.media.temp-images.enabled=false）");
        }
    }

    /** 临时图片目录（已解析为绝对路径并创建） */
    public Path tmpDir() {
        return requireInitialized(tmpDir, "临时图片目录");
    }

    /** 知识库图片目录（已解析为绝对路径并创建） */
    public Path kbDir() {
        return requireInitialized(kbDir, "知识库图片目录");
    }

    /** 图片缓存目录（tmp-images/inbound，已创建） */
    public Path inboundDir() {
        return requireInitialized(tmpDir, "临时图片目录").resolve(INBOUND_DIR_NAME);
    }

    private Path resolve(String key, String raw) {
        if (!StringUtils.hasText(raw)) {
            throw new IllegalStateException("[MEDIA] 配置错误：" + key + " 不能为空");
        }
        return Paths.get(raw).toAbsolutePath().normalize();
    }

    private void validatePositive(String key, int value) {
        if (value <= 0) {
            throw new IllegalStateException("[MEDIA] 配置错误：" + key + " 必须大于 0，当前为 " + value);
        }
    }

    private void ensureDirectory(Path dir, String label) {
        if (Files.isDirectory(dir)) {
            return;
        }
        try {
            Files.createDirectories(dir);
            log.info("[MEDIA] 已创建{}：{}", label, dir);
        } catch (IOException e) {
            throw new IllegalStateException("[MEDIA] 无法创建" + label + "：" + dir + "（" + e.getMessage() + "）", e);
        }
    }

    private Path requireInitialized(Path dir, String label) {
        if (dir == null) {
            throw new IllegalStateException("[MEDIA] " + label + "尚未初始化（MediaStorageGuard.init 未执行）");
        }
        return dir;
    }
}
