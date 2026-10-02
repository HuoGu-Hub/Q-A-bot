package com.example.qqbot.kb.map;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Component;

/**
 * 地图数据同步的 CLI 入口。
 *
 * <pre>
 * mvn spring-boot:run -Dspring-boot.run.arguments="--app.kb.map-sync.sync-on-start=true --spring.main.web-application-type=none"
 * </pre>
 *
 * <p>只想先看看有多少页变了（不取正文、不写库）：
 * 再加 {@code --app.kb.map-sync.dry-run=true}。
 *
 * <p>它**幂等且增量**：只处理版本变过的页；中途失败也不要紧，再跑一遍会从没成功的那些继续
 * （失败的页不会推进 revid）。
 */
@Component
@ConditionalOnProperty(name = "app.kb.map-sync.sync-on-start", havingValue = "true")
public class KbMapSyncRunner implements ApplicationRunner {

    private final KbMapSyncService service;
    private final ConfigurableApplicationContext context;

    public KbMapSyncRunner(KbMapSyncService service, ConfigurableApplicationContext context) {
        this.service = service;
        this.context = context;
    }

    @Override
    public void run(ApplicationArguments args) {
        boolean dryRun = args.containsOption("app.kb.map-sync.dry-run");
        try {
            KbMapSyncService.SyncReport r = service.sync(dryRun);
            System.out.println();
            System.out.println("================ wiki 地图数据同步 ================");
            System.out.printf("  命名空间页数    %d%n", r.pages());
            System.out.printf("  版本变过的页    %d%n", r.changed());
            System.out.printf("  已解析写入      %d%s%n", r.parsed(), r.dryRun() ? "（dryRun：只看数量）" : "");
            System.out.printf("  写入 marker     %d%n", r.markers());
            System.out.printf("  失败页          %d%n", r.failed());
            System.out.printf("  耗时            %d ms%n", r.elapsedMs());
            if (!r.byGroup().isEmpty()) {
                System.out.println("  ---- 各分组 marker 数（Top 20）----");
                r.byGroup().entrySet().stream().limit(20)
                        .forEach(e -> System.out.printf("    %-34s %d%n", e.getKey(), e.getValue()));
            }
            r.errors().forEach(e -> System.out.println("  ! " + e));
            System.out.println("==================================================");
        } catch (Exception e) {
            System.out.println("地图同步失败：" + e.getMessage());
        }
        System.exit(SpringApplication.exit(context, () -> 0));
    }
}
