package com.example.qqbot.eval;

import com.example.qqbot.persistence.EvalRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 首次启动时把仓库里的检索黄金集导进来，避免评测页一打开是空的。
 *
 * <p>只在**一个用例集都没有**时做一次：已有数据说明有人维护过，
 * 再自动导入会把人工标注覆盖成文件里的版本。
 *
 * <p>找不到文件就跳过 —— 评测页仍然可用，只是从空开始。
 */
@Component
public class EvalSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(EvalSeeder.class);

    private final EvalRepository repo;
    private final EvalDatasetService datasets;

    public EvalSeeder(EvalRepository repo, EvalDatasetService datasets) {
        this.repo = repo;
        this.datasets = datasets;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!repo.isAvailable()) {
            return;
        }
        try {
            repo.initSchema();
            if (!repo.listDatasets().isEmpty()) {
                return;
            }
            Path golden = locate("tests/golden/retrieval-golden.tsv");
            if (golden == null) {
                log.info("[EVAL] 未找到检索黄金集，评测页从空数据集开始");
                return;
            }
            EvalViews.Dataset dataset = datasets.create("golden", "检索黄金集",
                    "由 tests/golden/retrieval-golden.tsv 初始化", "system");
            EvalViews.ImportResult result = datasets.importTsv(dataset.id(),
                    Files.readString(golden, StandardCharsets.UTF_8), false);
            EvalViews.Version version = datasets.freeze(dataset.id(), "system");
            log.info("[EVAL] 已导入黄金集 {} 条（拒绝 {} 行），冻结版本 {}",
                    result.accepted(), result.rejected(), version == null ? "-" : version.version());
        } catch (Exception e) {
            log.warn("[EVAL] 初始化黄金集失败（评测页仍可用）：{}", e.getMessage());
        }
    }

    /** 从工作目录向上找：从 server/ 启动时黄金集在上一级 */
    private Path locate(String relative) {
        Path dir = Path.of("").toAbsolutePath();
        for (int i = 0; i < 3 && dir != null; i++) {
            Path candidate = dir.resolve(relative);
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
            dir = dir.getParent();
        }
        return null;
    }
}
