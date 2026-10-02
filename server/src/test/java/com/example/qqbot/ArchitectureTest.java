package com.example.qqbot;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * 架构护栏 —— 把 {@code server/README.md} 里的「四条结构铁律」从**文档**变成
 * **会失败的测试**。
 *
 * <h2>为什么需要它</h2>
 * 那四条铁律（onebot 是唯一协议细节处 / OneBotApiClient 是唯一出口 /
 * llm 是唯一知道用哪个模型的地方 / guard 是唯一有权说不的地方）方向都对，
 * 但过去只写在 README 里，**没有任何机制强制** —— 新人（和新 AI）改代码时
 * 无从知道边界在哪，只能是"读文档记得住就守，记不住就算了"。
 *
 * <h2>两类规则，别混为一谈</h2>
 * <ul>
 *   <li><b>已生效</b>：当前代码**本来就满足**，加进来是为了防退化。
 *       它们现在就应该是绿的；变红说明你刚引入了一个越界依赖。</li>
 *   <li><b>{@link Disabled}（目标）</b>：当前代码**确实违反**，是后续重构阶段
 *       （见 docs/md/重构总纲-目标架构与迁移路线.md）要还的债。
 *       每条都标了**实测的违反清单**，带 {@code @Disabled} 是为了不把
 *       已经红着的构建堵死；对应阶段做完后把注解摘掉即可。</li>
 * </ul>
 *
 * <p>本测试只分析**主代码**（{@link ImportOption.Predefined#DO_NOT_INCLUDE_TESTS}），
 * 测试类不受这些约束 —— 它们本来就要能随手构造内部组件。
 */
class ArchitectureTest {

    private static final JavaClasses CLASSES = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.example.qqbot");

    /* ==================== 已生效：防退化 ==================== */

    @Test
    @DisplayName("铁律③：只有 llm 能依赖模型 SDK（dev.langchain4j.model..）")
    void onlyLlmDependsOnModelSdk() {
        ArchRule rule = noClasses()
                .that().resideOutsideOfPackage("..llm..")
                .should().dependOnClassesThat().resideInAPackage("dev.langchain4j.model..");
        rule.check(CLASSES);
    }

    @Test
    @DisplayName("铁律①：业务包不得直接依赖 OneBot 的 HTTP 客户端")
    void businessPackagesDoNotTouchOneBotClient() {
        ArchRule rule = noClasses()
                .that().resideInAnyPackage("..agent..", "..kb..", "..qa..", "..command..", "..media..",
                        "..site..", "..publicapi..", "..settings..", "..admin..", "..logs..", "..guard..")
                .should().dependOnClassesThat().resideInAPackage("com.example.qqbot.onebot.client..");
        rule.check(CLASSES);
    }

    @Test
    @DisplayName("铁律④：guard 不得依赖 kb / qa / llm —— 它必须零成本、永远能跑")
    void guardStaysFreeOfExpensivePackages() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("..guard..")
                .should().dependOnClassesThat().resideInAnyPackage("..kb..", "..qa..", "..llm..");
        rule.check(CLASSES);
    }

    @Test
    @DisplayName("知识库不得反向依赖后台 / 配置中心 / 广场")
    void knowledgeDoesNotDependOnAdminSettingsPlaza() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("..kb..")
                .should().dependOnClassesThat()
                .resideInAnyPackage("..admin..", "..settings..", "..plaza..");
        rule.check(CLASSES);
    }

    /* ==================== 目标：还债清单 ==================== */

    @Test
    @Disabled("目标 Phase 1（S1 持久化去单点）。实测违反：8 个包共 12 个类直接持有/使用 java.sql —— CommandStore、KbBlockStore、CategoryStore、KbProposalService、KbTermStore、AnswerAggregator、PlazaAdminController、PlazaStore、QaAnalytics、QaStore、CarouselStore、SiteTextService")
    @DisplayName("【目标】持久化层之外不得出现 java.sql")
    void noJdbcOutsidePersistence() {
        ArchRule rule = noClasses()
                .that().resideOutsideOfPackage("..persistence..")
                .should().dependOnClassesThat().resideInAnyPackage("java.sql..", "javax.sql..");
        rule.check(CLASSES);
    }

    @Test
    @DisplayName("★ S1 第一步：kb 不得依赖 qa（原先有 4 个 Store 直接依赖 qa.QaStore）")
    void knowledgeDoesNotDependOnQa() {
        // 2026-10-02 转正：kb 侧原先 KbBlockStore / CategoryStore / KbProposalService / KbTermStore
        // 直接依赖 qa.QaStore —— 而它们要的只是"一个 SQLite 连接"，不是"问答记录"。
        // 换成 persistence.SqliteConnectionProvider 之后这条就成立了。
        // ⚠️ 反向（qa -> kb）仍在：QaCollector 要用 kb.Glossary / KbRetriever / KbTrace。
        //    那是另一个方向的问题（记录检索指标），需要把 KbTrace 挪成中性 DTO 才能解，见文档。
        ArchRule rule = noClasses()
                .that().resideInAPackage("..kb..")
                .should().dependOnClassesThat().resideInAPackage("..qa..");
        rule.check(CLASSES);
    }

    @Test
    @Disabled("目标 Phase 1。实测违反（由 ArchUnit 发现，import 扫描看不到）：kb.term.KbTermService 持有 ObjectProvider<publicapi.PublicSearchService>，并在 clearSearchCache() 里调 PublicSearchService.invalidate() —— 知识库反过来知道公开站的缓存，属于层次倒置；正确做法是知识库发出变更通知、公开站自己订阅")
    @DisplayName("【目标】kb 不得依赖 publicapi")
    void knowledgeDoesNotDependOnPublicApi() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("..kb..")
                .should().dependOnClassesThat().resideInAPackage("..publicapi..");
        rule.check(CLASSES);
    }

    @Test
    @Disabled("目标 Phase 1。实测违反：agent/ChatService 依赖 dev.langchain4j.data.message.ImageContent —— 视觉消息的构造细节漏出了 llm")
    @DisplayName("【目标】llm 之外不得出现任何 dev.langchain4j 类型")
    void noLangchain4jOutsideLlm() {
        ArchRule rule = noClasses()
                .that().resideOutsideOfPackage("..llm..")
                .should().dependOnClassesThat().resideInAPackage("dev.langchain4j..");
        rule.check(CLASSES);
    }

    @Test
    @Disabled("目标 Phase 1。实测违反：plaza/FallbackService 直接用 OneBotApiClient 发消息，绕过 OutboundFilter（出站敏感词过滤）与 OutboundPacer（节流/分片）—— 这是一条真实的出站治理缺口，不只是分层洁癖")
    @DisplayName("【目标】只有 onebot.outbound 与 router 能碰到 OneBotApiClient")
    void onlyOutboundPathSendsMessages() {
        ArchRule rule = noClasses()
                .that().resideOutsideOfPackages("..onebot.outbound..", "..router..")
                .and().doNotHaveSimpleName("QqbotServerApplication")
                .should().dependOnClassesThat().haveSimpleName("OneBotApiClient");
        rule.check(CLASSES);
    }

    @Test
    @Disabled("目标 Phase 1（S2 配置去中心化）。实测违反：全部 13 个包都直接注入 config 包类型；GuardProperties 单类被 17 个类引用、KbProperties 11、QaProperties 10")
    @DisplayName("【目标】业务包不得直接依赖 config（配置只应在装配层注入）")
    void businessPackagesDoNotDependOnConfig() {
        ArchRule rule = noClasses()
                .that().resideInAnyPackage("..agent..", "..kb..", "..qa..", "..command..", "..media..",
                        "..site..", "..publicapi..", "..admin..", "..logs..", "..guard..", "..llm..",
                        "..plaza..", "..onebot..", "..settings..", "..router..")
                .should().dependOnClassesThat().resideInAPackage("..config..");
        rule.check(CLASSES);
    }
}
