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
 * # 只看数量、不写库
 * mvn spring-boot:run -Dspring-boot.run.arguments="--app.kb.wiki-import.import-on-start=true --app.kb.wiki-import.dry-run=true --spring.main.web-application-type=none"
 *
 * # 真导入（增量：只处理 revid 变过的页）
 * mvn spring-boot:run -Dspring-boot.run.arguments="--app.kb.wiki-import.import-on-start=true --spring.main.web-application-type=none"
 *
 * # 回滚：清掉全部 wiki 文章块（含切出来的续块）与导入状态
 * mvn spring-boot:run -Dspring-boot.run.arguments="--app.kb.wiki-import.import-on-start=true --app.kb.wiki-import.purge=true --spring.main.web-application-type=none"
 * </pre>
 *
 * <h2>为什么 purge 入口是 2026-10-02 才补的</h2>
 * {@code WikiArticleImporter.purge()} 早就写好了，类注释里也写着「purge() 一条命令清干净」——
 * 但**这个入口没接上**：{@code LocationCorpusRunner} 有 {@code --...purge=true}，这里没有。
 * 于是「可回滚」只存在于测试里，运维手上没有那条命令。
 *
 * <p>扩充语料时这是硬伤：导一批进去发现检索变差，**退不回来**。
 * 能力没有入口，等于没有这个能力 —— 和地点语料那条教训一模一样。
 *
 * <p>同一个提交里还修了另一半：原先 {@code purge()} **不清 {@code kb_wiki_page} 的状态行**，
 * 而增量判据就是"revid 变过吗"。状态行留着 → 回滚后再导入**一篇都不处理** →
 * purge 是一扇单向门。现在 purge 会连状态一起清，完全回到"从没导入过"。
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
        boolean purge = args.containsOption("app.kb.wiki-import.purge");
        try {
            if (purge) {
                int n = importer.purge();
                System.out.println();
                System.out.println("================ wiki 文章回滚 ================");
                System.out.printf("  已清除文章块    %d 个（含切出来的续块）%n", n);
                System.out.println("  导入状态已清空 —— 可以重新导入");
                System.out.println("  ⚠️ 词条表里会留下这些块的**孤儿词条**（reconcile 只加不删）：");
                System.out.println("     去管理端「词条」面板点一次「清理孤儿词条」即可。");
                System.out.println("==============================================");
            } else {
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
                if (r.pages() == 0) {
                    System.out.println("  ⚠️ 一篇都没列到 —— 检查 app.kb.wiki-import.prefixes / categories 是否配了");
                }
                r.errors().stream().limit(10).forEach(e -> System.out.println("  ! " + e));
                System.out.println("==========================================================");
            }
        } catch (Exception e) {
            System.out.println((purge ? "回滚" : "导入") + "失败：" + e.getMessage());
        }
        System.exit(SpringApplication.exit(context, () -> 0));
    }
}
