package com.example.qqbot.kb.map;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Component;

/**
 * 地点语料派生的 CLI 入口。
 *
 * <pre>
 * # 只看会派生多少条，不写库
 * mvn spring-boot:run -Dspring-boot.run.arguments="--app.kb.location-corpus.build-on-start=true --app.kb.location-corpus.dry-run=true --spring.main.web-application-type=none"
 *
 * # 真派生（需先跑过地图同步）
 * mvn spring-boot:run -Dspring-boot.run.arguments="--app.kb.location-corpus.build-on-start=true --spring.main.web-application-type=none"
 *
 * # 回滚：清掉全部派生地点块
 * mvn spring-boot:run -Dspring-boot.run.arguments="--app.kb.location-corpus.build-on-start=true --app.kb.location-corpus.purge=true --spring.main.web-application-type=none"
 * </pre>
 *
 * <p><b>为什么需要这个入口</b>：派生逻辑早就有了，但**没有可执行入口** ——
 * 只有测试能跑它。运维要落这批语料时，翻文档会看到一条根本执行不了的命令。
 * 能力没有入口，等于没有这个能力。
 *
 * <p>依赖：先跑地图同步（{@code app.kb.map-sync.sync-on-start=true}）把 marker 灌进来，
 * 否则没有 marker 可派生（本入口会明确报出来，而不是静默产出 0 条）。
 */
@Component
@ConditionalOnProperty(name = "app.kb.location-corpus.build-on-start", havingValue = "true")
public class LocationCorpusRunner implements ApplicationRunner {

    private final LocationCorpusBuilder builder;
    private final ConfigurableApplicationContext context;

    public LocationCorpusRunner(LocationCorpusBuilder builder, ConfigurableApplicationContext context) {
        this.builder = builder;
        this.context = context;
    }

    @Override
    public void run(ApplicationArguments args) {
        boolean dryRun = args.containsOption("app.kb.location-corpus.dry-run");
        boolean purge = args.containsOption("app.kb.location-corpus.purge");
        try {
            if (purge) {
                int n = builder.purge();
                System.out.println();
                System.out.println("================ 地点语料回滚 ================");
                System.out.printf("  已清除派生块 %d 条%n", n);
                System.out.println("==============================================");
            } else {
                LocationCorpusBuilder.BuildReport r = dryRun ? builder.planOnly() : builder.build(false);
                System.out.println();
                System.out.println("================ 地点语料派生 ================");
                System.out.printf("  计划条数      %d%s%n", r.entries(), dryRun ? "（dryRun：只看数量，未写库）" : "");
                System.out.printf("    区域        %d%n", r.regions());
                System.out.printf("    POI 类型    %d%n", r.pois());
                System.out.printf("    具名地点    %d%n", r.places());
                System.out.printf("    NPC         %d%n", r.npcs());
                System.out.printf("  已写入        %d%n", r.written());
                System.out.printf("  排除 Lore     %d（刻意不派生：它们是叙事文本，不是地点）%n", r.skippedLore());
                if (r.entries() == 0) {
                    System.out.println("  ⚠️ 一条都没派生 —— 先确认跑过地图同步（app.kb.map-sync.sync-on-start=true）");
                }
                System.out.println("==============================================");
            }
        } catch (Exception e) {
            System.out.println("地点语料派生失败：" + e.getMessage());
        }
        System.exit(SpringApplication.exit(context, () -> 0));
    }
}
