package com.example.qqbot.llm;

import com.example.qqbot.kb.KbContextQueryTransformer;
import com.example.qqbot.kb.KbLookupTool;
import com.example.qqbot.kb.KbRetrievalDiagnostics;
import com.example.qqbot.kb.KbRetriever;
import dev.langchain4j.data.message.ImageContent;
import dev.langchain4j.invocation.InvocationParameters;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.service.UserMessage;

import java.util.ArrayList;
import java.util.List;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.tool.BeforeToolExecution;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * 带工具的对话助手 —— <b>官方 AiServices 装配的单一入口</b>。
 *
 * <p>它把四样东西接在一起：系统提示词来自 {@link LlmRouter}，模型入口是
 * {@link LlmRouterChatModel} 包出来的降级链，检索与注入是 {@link KbRetriever}
 * 驱动的那套官方管线，工具是 {@link KbLookupTool}。
 *
 * <h2>为什么由 AiServices 驱动</h2>
 * 工具执行循环里那些机制 —— 轮次上限、参数错误处理、执行失败处理、编造工具名的兜底、
 * 并发执行 —— 自己写等于再造一个 KbRetriever。用官方的，我们就只管接线。
 *
 * <h2>系统提示词不能让 AiServices 自己拼</h2>
 * 安全底座只有一份（{@code system.md}）。所以用 {@code systemMessageProvider}
 * 把 {@link LlmRouter#systemPromptWithStyle()} 交过去，而不是让它用默认的空提示词。
 *
 * <h2>两条路都已接上</h2>
 * 群聊的纯文本与带图两条路都走本类：文本走 {@link #chat}，图片走
 * {@link #chatWithImages}。检索、资料注入与工具执行因此只有一份实现，
 * 不存在「老路 + 新路」并存。
 */
@Component
public class KbToolAssistant {

    private static final Logger log = LoggerFactory.getLogger(KbToolAssistant.class);

    /**
     * 允许被模型调用的工具白名单 —— <b>默认拒绝，不是默认允许</b>。
     *
     * <p>新增工具必须同时改这里，否则模型调它会被当场拒绝并记日志。
     * 这条设计是刻意的：工具一旦注册进 {@code AiServices} 就能被模型触发，
     * 而"能不能被触发"与"该不该被触发"是两件事。
     */
    static final Set<String> ALLOWED_TOOLS = Set.of("lookup", "locate");

    /** 调用方身份 —— 授权钩子靠它判断"这是替谁在调工具" */
    public static final String KEY_CALLER = "tool.caller";

    /**
     * 助手接口：AiServices 要一个接口。
     *
     * <p>第二个参数是官方设计的通道 —— 参数类型为 {@link InvocationParameters} 时，
     * 框架把它透传到 {@code Query.metadata().invocationParameters()}，
     * transformer 因此能读到"真正用于检索的问题"与"诊断容器"。
     * 这两样都不能从用户消息里推出来：那条消息是信封，不是问题。
     */
    public interface Assistant {
        String chat(@UserMessage String message, InvocationParameters params);

        /**
         * 带图片的重载。
         *
         * <p><b>图片必须作为独立的 Content 参数传，不能塞进一个 {@code UserMessage} 再传。</b>
         * 框架只把带 {@code @UserMessage} 的参数当<b>模板</b>：非 Content 的参数会被
         * {@code InternalReflectionVariableResolver.asString()} 取 {@code toString()}，
         * 于是整条 {@code UserMessage} 被序列化成纯文本、{@code ImageContent} 一个都到不了模型
         * —— 而且不抛异常，只是静默地按文本回答。
         * 这条契约由 {@code KbToolAssistantTest.chatWithImagesKeepsImageContents} 钉住。
         *
         * <p>两个参数都带 {@code @UserMessage} 是官方支持的形状：
         * 第一个提供信封文本，第二个把图片并回同一条消息。
         */
        String chat(@UserMessage String message, @UserMessage List<ImageContent> images,
                    InvocationParameters params);
    }

    private final Assistant assistant;

    public KbToolAssistant(LlmRouter router, ChatModel chatModel, KbRetriever kbRetriever, KbLookupTool tool) {
        this.assistant = AiServices.builder(Assistant.class)
                .chatModel(chatModel)
                // 安全底座交给我们自己那份，不用官方默认的空提示词
                .systemMessageProvider(memoryId -> router.systemPromptWithStyle())
                // 检索与资料注入走我们的官方管线：ContentRetriever → ContentAggregator → ContentInjector
                .retrievalAugmentor(kbRetriever.augmentor())
                .tools(tool)
                // 防死循环：模型可以反复要求调工具，必须有上限
                .maxToolCallingRoundTrips(3)
                // ★ 授权钩子。模型能调工具之后，"说一句话"就是触发面 ——
                //   系统提示词里那些约束是写给模型的，**不能当授权机制**。
                .beforeToolExecution(this::authorize)
                .build();
    }

    /**
     * 调用前授权。放行记日志，不放行当场拒绝。
     *
     * <p>{@link BeforeToolExecution} 同时带着请求与 {@link dev.langchain4j.invocation.InvocationContext}，
     * 后者能拿到调用方传进来的参数。群聊入口已经按 {@code 群:xx/用户:xx} 把身份传进来
     * （见 {@code ChatService.callerOf}），所以这条判据今天就生效，不是"以后再说"。
     */
    void authorize(BeforeToolExecution execution) {
        String name = execution.request().name();
        if (!isAllowed(name)) {
            log.warn("[TOOL] 拒绝未授权工具：{}", name);
            throw new IllegalStateException("工具未授权：" + name);
        }
        // ★ 没有身份的调用一律拒绝。
        //
        //   工具是"替某个群友"执行的：说得出是谁，才谈得上追责，也才谈得上
        //   将来按人授权（只让管理员调写操作之类）。今天还没有按人策略，
        //   但"无身份就拒绝"这条必须现在就立起来 —— 否则将来任何新入口
        //   只要忘了传身份，就能静默地把工具用起来。
        if (callerOf(execution).isBlank()) {
            log.warn("[TOOL] 拒绝无身份的调用：{}", name);
            throw new IllegalStateException("工具调用缺少调用方身份");
        }
        String args = execution.request().arguments();
        log.info("[TOOL] 放行工具 {}（调用方 {}，参数 {} 字）", name, callerOf(execution),
                args == null ? 0 : args.length());
    }

    /** 从调用上下文里取身份。取不到一律当"身份不明"，不猜 */
    private static String callerOf(BeforeToolExecution execution) {
        try {
            InvocationParameters p = execution.invocationContext() == null
                    ? null : execution.invocationContext().invocationParameters();
            Object c = p == null ? null : p.get(KEY_CALLER);
            return c instanceof String s ? s : "";
        } catch (Exception e) {
            return "";
        }
    }

    /** 默认拒绝 */
    static boolean isAllowed(String toolName) {
        return toolName != null && ALLOWED_TOOLS.contains(toolName);
    }

    /** 不带检索上下文的一问一答 —— 检索问题就取消息本身，诊断不回流 */
    public String chat(String question) {
        return assistant.chat(question, new InvocationParameters());
    }

    /**
     * 带检索上下文的一问一答。
     *
     * @param message 给模型的消息，通常是信封（发言者 / 引用 / 用户内容）
     * @param kbQuery 真正用于检索的问题。与 {@code message} 分开是<b>必须</b>的：
     *                拿信封去检索会让多出来的文字稀释查询向量、污染术语匹配，
     *                而且不报错，只会答得变差
     * @param sink    诊断容器；调用返回后可从中读命中块与最高余弦做统计
     */
    public String chat(String message, String kbQuery, KbRetrievalDiagnostics sink) {
        return chat(message, kbQuery, sink, true);
    }

    /**
     * 带检索上下文与开关的一问一答 —— 群聊路径用的就是这一条。
     *
     * @param kbEnabled {@code false} = 本次不要资料（后台"不用知识库"模式）。
     *                  管线仍然照走，只是拿到一个空语料快照 —— 编排只有一条，
     *                  "不用资料"是喂给它的数据不同，不是另一条路
     */
    public String chat(String message, String kbQuery, KbRetrievalDiagnostics sink, boolean kbEnabled) {
        return chat(message, kbQuery, sink, kbEnabled, "");
    }

    /**
     * 带调用方身份的入口 —— <b>群聊路径用这一条</b>。
     *
     * @param caller 调用方身份，如 {@code 群:123/用户:456}。空 = 身份不明，
     *               授权钩子会拒绝一切工具调用（见 {@link #authorize}）
     */
    public String chat(String message, String kbQuery, KbRetrievalDiagnostics sink, boolean kbEnabled,
                       String caller) {
        return assistant.chat(message, params(kbQuery, sink, kbEnabled, caller));
    }

    /**
     * 视觉入口：带图片的一问一答。
     *
     * <p>图片参数是 {@link VisionImage} 这个<b>中性类型</b> —— 业务层只说
     * "这是一张 base64 + mimeType 的图"，转成官方 {@code ImageContent} 只发生在这里。
     * 那样 {@code agent} 包就不必 import langchain4j 的图片类型。
     */
    public String chatWithImages(String message, List<VisionImage> images, String kbQuery,
                                 KbRetrievalDiagnostics sink, boolean kbEnabled, String caller) {
        // ⚠️ 这里**不能**自己拼一条多内容 UserMessage 再传 —— 它会被框架当模板 toString 掉。
        //    见 Assistant 的第二个重载：文本走 message，图片走 List<ImageContent> 参数。
        List<ImageContent> contents = new ArrayList<>();
        if (images != null) {
            for (VisionImage img : images) {
                contents.add(ImageContent.from(img.base64(), img.mimeType()));
            }
        }
        return assistant.chat(message == null ? "" : message, contents,
                params(kbQuery, sink, kbEnabled, caller));
    }

    private static InvocationParameters params(String kbQuery, KbRetrievalDiagnostics sink,
                                               boolean kbEnabled, String caller) {
        InvocationParameters params = new InvocationParameters();
        params.put(KbContextQueryTransformer.KEY_QUERY, kbQuery);
        params.put(KbContextQueryTransformer.KEY_SINK, sink);
        params.put(KbContextQueryTransformer.KEY_ENABLED, kbEnabled);
        params.put(KEY_CALLER, caller == null ? "" : caller);
        return params;
    }
}
