package com.example.qqbot.kb.wiki;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Component;

/**
 * wiki 文章导入的 CLI 入口。
 *
 * <pre>
 * java -jar qqbot-server.jar \
 *   --spring.main.web-application-type=none \
 *   --app.persistence.db=/path/to/qqbot.sqlite \
 *   --app.kb.wiki-import.run-on-start=true
 * </pre>
 *
 * <p>加 {@code --app.kb.wiki-import.dry-run=true} 只比对 revid、不取正文也不写库；
 * 加 {@code --app.kb.wiki-import.force=true} 忽略 revid 全部重洗。
 *
 * <p>它跑完就退出：本地拿一份线上库的备份、跑完再导出 wiki 表，是"服务器访问不了
 * wiki 时仍能更新语料"的离线通道。导入本身幂等且增量，中途失败再跑一遍会继续。
 */
@Component
@ConditionalOnProperty(name = "app.kb.wiki-import.run-on-start", havingValue = "true")
public class WikiImportRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(WikiImportRunner.class);

    private final WikiArticleImporter importer;
    private final ConfigurableApplicationContext context;

    public WikiImportRunner(WikiArticleImporter importer, ConfigurableApplicationContext context) {
        this.importer = importer;
        this.context = context;
    }

    @Override
    public void run(ApplicationArguments args) {
        boolean dryRun = args.containsOption("app.kb.wiki-import.dry-run");
        boolean force = args.containsOption("app.kb.wiki-import.force");
        int code = 0;
        try {
            WikiArticleImporter.ImportReport r = importer.importAll(dryRun, force,
                    (phase, done, total) -> {
                        // 只在进度节点打一行，避免 981 页刷屏
                        if (done == 0 || done == total || done % 25 == 0) {
                            System.out.printf("[WIKI-IMPORT] %s %d/%d%n", phase, done, total);
                        }
                    });
            System.out.println("================ wiki 文章导入 ================");
            System.out.printf("  来源        %d%n", r.sources());
            System.out.printf("  页面        %d（跨来源去重跳过 %d）%n", r.pages(), r.duplicates());
            System.out.printf("  版本变过    %d%n", r.changed());
            System.out.printf("  写入块      %d%s%n", r.imported(), r.dryRun() ? "（dryRun：未写库）" : "");
            System.out.printf("  正文字数    %d%n", r.chars());
            System.out.printf("  渲染兜底    %d%n", r.rendered());
            System.out.printf("  失败        %d%n", r.failed());
            if (!r.bySource().isEmpty()) {
                System.out.println("  ---- 各来源页数（Top 20）----");
                r.bySource().entrySet().stream().limit(20)
                        .forEach(e -> System.out.printf("    %-34s %d%n", e.getKey(), e.getValue()));
            }
            if (!r.errors().isEmpty()) {
                System.out.println("  ---- 错误（最多 20 条）----");
                r.errors().stream().limit(20).forEach(e -> System.out.println("    ! " + e));
            }
            System.out.println("===============================================");
            if (r.failed() > 0) {
                code = 1;
            }
        } catch (Throwable t) {
            log.error("[WIKI-IMPORT] 导入失败", t);
            System.out.println("导入失败：" + t);
            code = 1;
        }
        final int exitCode = code;
        System.exit(SpringApplication.exit(context, () -> exitCode));
    }
}
