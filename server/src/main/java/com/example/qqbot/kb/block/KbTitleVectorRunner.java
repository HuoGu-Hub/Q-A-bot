package com.example.qqbot.kb.block;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Component;

/**
 * 补建标题向量（C 路）—— 一条命令补齐 / 刷新全库的标题向量。
 *
 * <pre>
 * mvn spring-boot:run -Dspring-boot.run.arguments="--app.kb.title-index.enabled=true --spring.main.web-application-type=none"
 * </pre>
 *
 * <p>只想先看看要补多少条（不调模型、不花钱）：
 * 再加一个 {@code --app.kb.title-index.dry-run=true}。
 *
 * <p>它是**幂等**的：只处理"从来没有标题向量"和"标题变了导致过期"的块。
 * 中途失败也不要紧，再跑一遍会从没补上的那些继续。
 *
 * <p>管理端也有同一个入口（{@code POST /admin/api/kb/blocks/title-vectors/backfill}）——
 * CLI 适合部署时跑，界面适合平时补。
 */
@Component
@ConditionalOnProperty(name = "app.kb.title-index.enabled", havingValue = "true")
public class KbTitleVectorRunner implements ApplicationRunner {

    private final KbBlockAdminService service;
    private final ConfigurableApplicationContext context;

    public KbTitleVectorRunner(KbBlockAdminService service, ConfigurableApplicationContext context) {
        this.service = service;
        this.context = context;
    }

    @Override
    public void run(ApplicationArguments args) {
        boolean dryRun = args.containsOption("app.kb.title-index.dry-run");
        try {
            KbBlockAdminService.TitleBackfill r = service.backfillTitleVectors(dryRun);
            System.out.println();
            System.out.println("================ 标题向量（C 路）补建 ================");
            System.out.printf("  块总数        %d%n", r.blocks());
            System.out.printf("  缺标题向量    %d%n", r.missing());
            System.out.printf("  标题已变过期  %d%n", r.stale());
            System.out.printf("  本次补建      %d%s%n", r.embedded(),
                    r.dryRun() ? "（dryRun：只看数量，没花钱）" : "");
            System.out.println("=====================================================");
        } catch (Exception e) {
            System.out.println("补建失败：" + e.getMessage());
        }
        System.exit(SpringApplication.exit(context, () -> 0));
    }
}
