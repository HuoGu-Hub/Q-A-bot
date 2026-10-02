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

    @Test
    @DisplayName("★ S2 第一步：业务包不得再注入 GuardProperties 这个 661 行的巨型配置对象")
    void noGodConfigObjectInBusinessCode() {
        // 2026-10-02 转正：原先 GuardProperties 被 17 个类注入（fan-in 第一），
        // 但其中 12 个只用到一个嵌套对象。现在它们注入 GuardProperties.RateLimit 这类小对象。
        // 这条规则防的是"图省事又把整个 GuardProperties 拖回来"。
        // 允许的例外：config（定义者）、settings（配置中心要按反射读写全部配置）、
        // 以及组合根 QqbotServerApplication（它显式 @EnableConfigurationProperties）。
        // 例外说明：
        //   config       —— 定义者
        //   settings     —— 配置中心要按反射读写全部配置
        //   QqbotServerApplication —— 组合根，显式 @EnableConfigurationProperties
        //   GuardPipeline —— guard 子系统自己的编排者，它要的是根上的 enabled / kill-switch
        //                    （子系统的总开关，不属于任何一个嵌套分组），这是合理依赖
        ArchRule rule = noClasses()
                .that().resideOutsideOfPackages("..config..", "..settings..")
                .and().doNotHaveSimpleName("QqbotServerApplication")
                .and().doNotHaveSimpleName("GuardPipeline")
                .should().dependOnClassesThat().haveSimpleName("GuardProperties");
        rule.check(CLASSES);
    }

    @Test
    @DisplayName("★ qa 不得依赖 kb —— 记录系统不该认识检索实现（新增）")
    void qaDoesNotDependOnKb() {
        // 2026-10-02 新增：QaCollector 原先 import kb.Glossary / kb.KbRetriever / kb.KbTrace，
        // 根因是 **KbTrace 内嵌了 KbRetriever.Retrieval 与 KbRetriever.Hit** ——
        // 记录系统为了读一份统计，被迫依赖整个检索实现。
        // 修法：
        //   1) KbTrace 扁平化成零依赖的纯数据，搬到中性 trace 包
        //   2) 术语匹配与"是否落在检索结果里"由检索层算好放进 trace
        //      （数据在哪，算法就该在哪）
        //   3) 从 Retrieval 造 trace 的那一步留在 kb（KbRetriever.traceOf）
        ArchRule rule = noClasses()
                .that().resideInAPackage("..qa..")
                .should().dependOnClassesThat().resideInAPackage("..kb..");
        rule.check(CLASSES);
    }

    /* ==================== 目标：还债清单 ==================== */

    @Test
    // 2026-10-02 转正：原先这里挂着 @Disabled，理由是"8 个包共 12 个类直接持有 java.sql"。
    // 逐个搬进 persistence/XxxRepository 之后，业务侧一个都不剩 ——
    // 这条规则从此是「会失败的测试」，而不是「目标」。
    @DisplayName("★ 持久化层之外不得出现 java.sql（2026-10-02 转正）")
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
    @DisplayName("★ kb 不得依赖 publicapi（层次倒置已拆）")
    // 2026-10-02 转正：KbTermService 原先持有 ObjectProvider<publicapi.PublicSearchService>
    // 并直接调 invalidate() —— 知识库反过来知道"公开站有检索缓存"，且那个
    // ObjectProvider 本身就是为绕循环依赖打的补丁。
    // 修法：知识库只发 KnowledgeChangedEvent，公开站自己 @EventListener 订阅。
    void knowledgeDoesNotDependOnPublicApi() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("..kb..")
                .should().dependOnClassesThat().resideInAPackage("..publicapi..");
        rule.check(CLASSES);
    }

    @Test
    @DisplayName("★ 铁律③（加强）：llm 之外不得出现任何 dev.langchain4j 类型")
    // 2026-10-02 转正：agent/ChatService 原先为了组装图片列表 import 了
    // dev.langchain4j.data.message.ImageContent。
    // 修法：新增中性的 llm.VisionImage(base64, mimeType)，转换只发生在 LlmRouter 内部。
    void noLangchain4jOutsideLlm() {
        ArchRule rule = noClasses()
                .that().resideOutsideOfPackage("..llm..")
                .should().dependOnClassesThat().resideInAPackage("dev.langchain4j..");
        rule.check(CLASSES);
    }

    @Test
    @DisplayName("★ 铁律②：只有 onebot.outbound 与 router 能碰到 OneBotApiClient")
    // 2026-10-02 转正：plaza/FallbackService 原先把消息**直接**发给协议客户端，
    // 绕过了出站敏感词过滤与节流 —— 真实的治理缺口。
    // 修法不是"在 FallbackService 里补一次过滤"，而是**让发送器成为唯一出口**：
    //   - 出站过滤从 MessageRouter 挪进 OutboundSender（放在调用方就得靠每个人记得调）
    //   - 新增 OutboundSender.sendToGroup(群号, 文本) 给没有入站事件的场景
    // 于是任何发送路径都绕不过过滤与节流。
    void onlyOutboundPathSendsMessages() {
        ArchRule rule = noClasses()
                .that().resideOutsideOfPackages("..onebot.outbound..", "..router..")
                .and().doNotHaveSimpleName("QqbotServerApplication")
                .should().dependOnClassesThat().haveSimpleName("OneBotApiClient");
        rule.check(CLASSES);
    }

    @Test
    @Disabled("目标 Phase 1（S2 配置去中心化）。2026-10-02 **摘掉 @Disabled 真跑了一次**："
            + "62 处违反 / 11 个类。剩下的：CarouselService 10、PublicController 9、"
            + "MediaStorageGuard 29、GuardPipeline 23、EmbeddingClient 17、RerankClient 16。"
            + "⚠️「违反处数」与「类数」是两件事 —— LlmRouter 一个类就占 12%（它把整个 "
            + "LlmProperties 拖进构造器、然后到处读字段）。"
            + "⚠️ 别用 grep import 估这个数字：既漏全限定名用法、又把死 import 算进去，"
            + "而 ArchUnit 看的是字节码依赖")
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
