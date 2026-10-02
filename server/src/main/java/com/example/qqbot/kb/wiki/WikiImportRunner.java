package com.example.qqbot.kb.wiki;

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
 * mvn spring-boot:run -Dspring-boot.run.arguments="--app.kb.wiki-import.import-on-start=true --spring.main.web-application-type=none"
 * </pre>
 *
 * <p>只看数量不写库：再加 {@code --app.kb.wiki-import.dry-run=true}。
 *
 * <p>幂等且增量：只处理 `revid` 变过的页；中途失败再跑一遍会从没成功的那些继续。
 */
@Component
@ConditionalOnProperty(name = "app.kb.wiki-import.import-on-start", havingValue = "true")
public class WikiImportRunner implements ApplicationRunner {

    private final WikiArticleImporter importer;
    private final ConfigurableApplicationContext context;

    public WikiImportRunner(WikiArticleImporter importer, ConfigurableApplicationContext context) {
        this.importer = importer;
        this.context = context;
    }

    @Override
    public void run(ApplicationArguments args) {
        boolean dryRun = args.containsOption("app.kb.wiki-import.dry-run");
        try {
            WikiArticleImporter.ImportReport r = importer.importAll(dryRun);
            System.out.println();
            System.out.println("================ wiki 文章导入（任务 / 机制）================");
            System.out.printf("  来源数        %d%n", r.sources());
            System.out.printf("  页面总数      %d%n", r.pages());
            System.out.printf("  版本变过      %d%n", r.changed());
            System.out.printf("  已写入块      %d%s%n", r.imported(), r.dryRun() ? "（dryRun：只看数量）" : "");
            System.out.printf("  正文字数      %d%n", r.chars());
            System.out.printf("  失败          %d%n", r.failed());
            if (!r.bySource().isEmpty()) {
                System.out.println("  ---- 各来源页面数 ----");
                r.bySource().forEach((k, v) -> System.out.printf("    %-28s %d%n", k, v));
            }
            r.errors().stream().limit(10).forEach(e -> System.out.println("  ! " + e));
            System.out.println("==========================================================");
        } catch (Exception e) {
            System.out.println("导入失败：" + e.getMessage());
        }
        System.exit(SpringApplication.exit(context, () -> 0));
    }
}
