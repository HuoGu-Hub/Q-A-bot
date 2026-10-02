package com.example.qqbot.media;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.example.qqbot.config.GuardProperties;
import com.example.qqbot.config.MediaProperties;
import com.example.qqbot.guard.PathGuard;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 临时图片清理的验收测试，对应 plans/待办事项.md 第 1 项的四条验收标准。
 */
class TempImageCleanerTest {

    private static final long HOUR = 3600_000L;
    private static final long KB = 1024L;

    @TempDir
    Path base;

    private Path tmp;
    private Path kb;
    private MediaProperties props;
    private PathGuard pathGuard;
    private MediaStorageGuard storage;
    private TempImageCleaner cleaner;

    @BeforeEach
    void setUp() {
        tmp = base.resolve("data/tmp-images");
        kb = base.resolve("data/kb-images");
        props = new MediaProperties();
        props.getTempImages().setDir(tmp.toString());
        props.getKbImages().setDir(kb.toString());

        GuardProperties guard = new GuardProperties();
        guard.getFileAccess().setAllowedRoots(List.of(base.toString()));
        pathGuard = new PathGuard(guard);

        storage = new MediaStorageGuard(props, pathGuard);
        storage.init();
        ImageCache cache = new ImageCache(storage, props, pathGuard);
        cache.init();
        cleaner = new TempImageCleaner(props, storage, pathGuard, cache);
    }

    @Test
    @DisplayName("验收①：超过保留时长的临时文件被删，没过期的保留")
    void deletesExpiredAndKeepsFresh() throws IOException {
        Path expired = writeFile(tmp.resolve("outbound/old.jpg"), "0123456789", 25 * HOUR);
        Path fresh = writeFile(tmp.resolve("inbound/new.jpg"), "0123456789", 1 * HOUR);

        TempImageCleaner.CleanupReport report = cleaner.cleanup();

        assertThat(expired).doesNotExist();
        assertThat(fresh).exists();
        assertThat(report.candidates()).isEqualTo(1);
        assertThat(report.deleted()).isEqualTo(1);
        assertThat(report.failed()).isZero();
        assertThat(report.freedBytes()).isEqualTo(10);
    }

    @Test
    @DisplayName("验收②：知识库图片永不删除")
    void neverTouchesKbImages() throws IOException {
        // 故意做成"又老又大"，如果隔离失效，它一定是第一个被删的
        Path kbImage = writeFile(kb.resolve("manual/cover.jpg"), "0123456789", 25 * 24 * HOUR);
        Path expiredTmp = writeFile(tmp.resolve("old.jpg"), "0123456789", 25 * HOUR);

        cleaner.cleanup();

        assertThat(kbImage).exists();
        assertThat(expiredTmp).doesNotExist();
    }

    @Test
    @DisplayName("验收④：日志能看出删了几个、释放了多少空间")
    void logsDeletedCountAndFreedSpace() throws IOException {
        writeFile(tmp.resolve("big.jpg"), "x".repeat((int) (3 * KB)), 25 * HOUR);

        Logger logger = (Logger) LoggerFactory.getLogger(TempImageCleaner.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            cleaner.cleanup();
        } finally {
            logger.detachAppender(appender);
        }

        String logs = appender.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .collect(Collectors.joining("\n"));
        assertThat(logs).contains("删除 1 个").contains("释放 3.0 KB").contains("清理空目录");
    }

    @Test
    @DisplayName("单轮删除上限生效，剩下的留到下一轮")
    void respectsMaxDeletePerRun() throws IOException {
        for (int i = 1; i <= 5; i++) {
            writeFile(tmp.resolve("old-" + i + ".jpg"), "x", 25 * HOUR);
        }
        props.getTempImages().setMaxDeletePerRun(2);

        TempImageCleaner.CleanupReport first = cleaner.cleanup();
        assertThat(first.candidates()).isEqualTo(5);
        assertThat(first.deleted()).isEqualTo(2);
        assertThat(Files.list(tmp).count()).isEqualTo(3);

        TempImageCleaner.CleanupReport second = cleaner.cleanup();
        assertThat(second.deleted()).isEqualTo(2);
        assertThat(Files.list(tmp).count()).isEqualTo(1);
    }

    @Test
    @DisplayName("PathGuard 拦下的文件不删（第二道防线仍然生效）")
    void skipsFilesDeniedByPathGuard() throws IOException {
        Path denied = writeFile(tmp.resolve("secret.log"), "x", 25 * HOUR);
        Path normal = writeFile(tmp.resolve("normal.jpg"), "x", 25 * HOUR);

        TempImageCleaner.CleanupReport report = cleaner.cleanup();

        assertThat(denied).exists();
        assertThat(normal).doesNotExist();
        assertThat(report.skipped()).isEqualTo(1);
        assertThat(report.deleted()).isEqualTo(1);
    }

    @Test
    @DisplayName("顺带清掉空的子目录，但不动根目录")
    void removesEmptySubdirectories() throws IOException {
        Path emptyDir = Files.createDirectories(tmp.resolve("outbound"));
        writeFile(tmp.resolve("inbound/new.jpg"), "x", 1 * HOUR);

        cleaner.cleanup();

        assertThat(emptyDir).doesNotExist();
        assertThat(tmp).exists();
        assertThat(tmp.resolve("inbound/new.jpg")).exists();
    }

    @Test
    @DisplayName("enabled=false 时完全不动手")
    void disabledDoesNothing() throws IOException {
        Path expired = writeFile(tmp.resolve("old.jpg"), "x", 25 * HOUR);
        props.getTempImages().setEnabled(false);

        TempImageCleaner.CleanupReport report = cleaner.cleanup();

        assertThat(expired).exists();
        assertThat(report.deleted()).isZero();
    }

    private Path writeFile(Path path, String content, long ageMillis) throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path, content);
        Files.setLastModifiedTime(path, FileTime.fromMillis(System.currentTimeMillis() - ageMillis));
        return path;
    }
}
