package com.example.qqbot.llm;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.Content;
import dev.langchain4j.data.message.ImageContent;
import com.example.qqbot.guard.BudgetGuard;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.openai.OpenAiChatModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 多厂商大模型路由。
 *
 * <p>做三件事：
 * <ol>
 *   <li>启动时按配置建好每个厂商的 ChatModel（没填 api-key 的自动跳过）</li>
 *   <li>调用时按「默认 provider → 降级链」的顺序尝试，谁先成功用谁</li>
 *   <li>需要看图时，只在标了 @B@vision@B@ 能力的 provider 里挑</li>
 * </ol>
 *
 * <p>为什么能这么简单：<b>几乎所有厂商都兼容 OpenAI 协议</b>，
 * 所以一个 OpenAiChatModel 换个 baseUrl 就是一个新厂商，不需要写适配器。
 */
@Component
public class LlmRouter {

    private static final Logger log = LoggerFactory.getLogger(LlmRouter.class);

    /** 能力标签 */
    public static final String CAP_VISION = "vision";

    /**
     * provider 名 → 模型客户端。
     *
     * <p>用 ConcurrentHashMap：支持**热替换**（后台改完模型 ID 立即生效），
     * 替换时正在进行的请求持有的旧引用仍然有效，天然无缝。
     */
    private final Map<String, ChatModel> models = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<String, Provider> configs = new java.util.concurrent.ConcurrentHashMap<>();
    private volatile List<String> chain = new ArrayList<>();
    private final String systemPrompt;

    /**
     * 留着整个 properties 引用，是为了让「回复风格」能<b>热生效</b>：
     * 系统提示词本身读一次就够了，但风格要每轮现取。
     */
    private final LlmPolicy props;

    /** 记 token 用量给成本预算（D6）。token 只有这一层看得到 */
    private final BudgetGuard budgetGuard;

    /** 构建模型客户端 —— 启动时和热替换时共用同一套参数 */
    private static ChatModel buildClient(Provider cfg) {
        OpenAiChatModel.OpenAiChatModelBuilder builder = OpenAiChatModel.builder()
                .baseUrl(cfg.getBaseUrl())
                .apiKey(cfg.getApiKey())
                .modelName(cfg.getModelName())
                .temperature(cfg.getTemperature())
                .timeout(Duration.ofSeconds(cfg.getTimeoutSeconds()))
                .maxRetries(cfg.getMaxRetries())
                .logRequests(Boolean.TRUE.equals(cfg.getLogRequests()))
                .logResponses(Boolean.TRUE.equals(cfg.getLogRequests()));

        if (cfg.getMaxTokens() != null && cfg.getMaxTokens() > 0) {
            builder.maxTokens(cfg.getMaxTokens());
        }
        if (StringUtils.hasText(cfg.getReasoningEffort())) {
            builder.reasoningEffort(cfg.getReasoningEffort());
        }
        if (cfg.getHeaders() != null && !cfg.getHeaders().isEmpty()) {
            builder.customHeaders(cfg.getHeaders());
        }
        return builder.build();
    }

    public LlmRouter(LlmPolicy props, ResourceLoader resourceLoader, BudgetGuard budgetGuard) {
        this.props = props;
        this.budgetGuard = budgetGuard;
        this.systemPrompt = loadSystemPrompt(props, resourceLoader);

        for (Map.Entry<String, Provider> entry : props.getProviders().entrySet()) {
            String name = entry.getKey();
            Provider cfg = entry.getValue();

            if (!StringUtils.hasText(cfg.getApiKey())) {
                log.info("[LLM] 跳过 {}：未配置 api-key", name);
                continue;
            }
            if (!StringUtils.hasText(cfg.getBaseUrl()) || !StringUtils.hasText(cfg.getModelName())) {
                log.warn("[LLM] 跳过 {}：base-url 或 model-name 没填", name);
                continue;
            }

            models.put(name, buildClient(cfg));
            configs.put(name, cfg);
            log.info("[LLM] provider 就绪：{}  model={}  能力={}  maxTokens={}  推理强度={}  baseUrl={}",
                    name, cfg.getModelName(), cfg.getCapabilities(),
                    cfg.getMaxTokens() != null && cfg.getMaxTokens() > 0 ? cfg.getMaxTokens() : "服务商默认",
                    StringUtils.hasText(cfg.getReasoningEffort()) ? cfg.getReasoningEffort() : "默认",
                    cfg.getBaseUrl());
        }

        buildFallbackChain(props);

        if (models.isEmpty()) {
            log.warn("[LLM] 没有任何可用的 provider —— 机器人会回复「还没配置大模型」。"
                    + "请在 .env 里填任意一个厂商的 api-key。");
        } else {
            log.info("[LLM] 降级链：{}", String.join(" -> ", chain));
            log.info("[LLM] 能看图的模型：{}", hasVision() ? "有" : "没有（图片会被礼貌拒绝）");
        }
    }

    /** 组装调用顺序：默认 provider → 配置的降级链 → 其余可用的（兜底） */
    private void buildFallbackChain(LlmPolicy props) {
        List<String> newChain = new ArrayList<>();
        if (StringUtils.hasText(props.getDefaultProvider())) {
            newChain.add(props.getDefaultProvider());
        }
        if (props.getFallbackProviders() != null) {
            newChain.addAll(props.getFallbackProviders());
        }
        for (String name : models.keySet()) {
            if (!newChain.contains(name)) {
                newChain.add(name);
            }
        }
        newChain.removeIf(name -> !models.containsKey(name));
        this.chain = newChain;
    }

    // ==================== 热替换（后台改模型 ID 用）====================

    /**
     * 用新的模型 ID 重建某个 provider 的客户端，**立即生效**。
     *
     * <p><b>为什么能热生效</b>：{@code models} 是个 Map，替换其中一项即可 ——
     * 正在进行的请求持有的旧引用仍然有效，天然无缝。不像端口/线程池那样
     * 绑定了系统资源。
     *
     * <p>调用方应当**先测试再替换**（见 {@code /admin/api/models/test}），
     * 避免"改错就哑"。
     *
     * @return 旧的模型 ID（可用于回滚）
     */
    public String swapModelName(String provider, String newModelName) {
        Provider cfg = configs.get(provider);
        if (cfg == null) {
            throw new IllegalArgumentException("没有这个厂商：" + provider);
        }
        if (!StringUtils.hasText(newModelName)) {
            throw new IllegalArgumentException("模型 ID 不能为空");
        }

        String old = cfg.getModelName();
        cfg.setModelName(newModelName.trim());
        models.put(provider, buildClient(cfg));

        log.info("[LLM] 模型已热替换：{}  {} -> {}", provider, old, newModelName);
        return old;
    }

    /** 回滚到旧模型 ID（测试失败时用） */
    public void rollbackModelName(String provider, String oldModelName) {
        try {
            swapModelName(provider, oldModelName);
            log.warn("[LLM] 模型已回滚：{} -> {}", provider, oldModelName);
        } catch (Exception e) {
            log.error("[LLM] 回滚失败！{} —— {}", provider, e.getMessage());
        }
    }

    /** 某个 provider 当前的模型 ID */
    public String currentModelName(String provider) {
        Provider cfg = configs.get(provider);
        return cfg == null ? null : cfg.getModelName();
    }

    /** 已就绪的 provider 名单 */
    public java.util.Set<String> readyProviders() {
        return java.util.Set.copyOf(models.keySet());
    }

    /**
     * 用一个临时客户端测试某个模型是否可用。
     *
     * <p><b>不改动任何现有状态</b> —— 只是建个临时客户端发一个最小请求，
     * 通了才建议调用方去热替换。这是"改错就哑"的第一道防线。
     *
     * @return {ok, reply/excerpt, elapsedMs}
     */
    public java.util.Map<String, Object> probe(String provider, String modelName, String prompt) {
        Provider base = configs.get(provider);
        if (base == null) {
            return java.util.Map.of("ok", false, "error", "没有这个厂商：" + provider);
        }
        // 复制一份配置，只换模型名 —— 不动原对象
        Provider probeCfg = base.copy();
        probeCfg.setModelName(modelName);
        probeCfg.setMaxRetries(0);
        probeCfg.setTimeoutSeconds(Math.min(base.getTimeoutSeconds(), 30));
        // 测试不需要流式，也不需要 reasoning 的特殊处理
        probeCfg.setLogRequests(false);

        long t0 = System.currentTimeMillis();
        try {
            ChatModel temp = buildClient(probeCfg);
            String question = StringUtils.hasText(prompt) ? prompt : "回复「ok」两个字即可";
            List<ChatMessage> probeMessages = List.of(
                    SystemMessage.from("你是连通性测试，只回复用户要求的最短内容。"),
                    UserMessage.from(question));
            ChatResponse response = temp.chat(probeMessages);
            String text = response == null || response.aiMessage() == null
                    ? "" : String.valueOf(response.aiMessage().text());
            long ms = System.currentTimeMillis() - t0;

            if (text == null || text.isBlank()) {
                // 推理模型给太少 max-tokens 会返回空 —— 这个坑踩过
                return java.util.Map.of("ok", false, "elapsedMs", ms,
                        "error", "模型返回了空内容。若是推理模型，可能是 max-tokens 太小或模型 ID 不对");
            }
            return java.util.Map.of("ok", true, "elapsedMs", ms,
                    "reply", text.length() > 200 ? text.substring(0, 200) + "…" : text);
        } catch (Exception e) {
            return java.util.Map.of("ok", false,
                    "elapsedMs", System.currentTimeMillis() - t0,
                    "error", String.valueOf(e.getMessage()));
        }
    }

    /** 当前降级链（只读展示） */
    public List<String> fallbackChain() {
        return List.copyOf(chain);
    }

    /**
     * 加载系统提示词（{@code AGENTS.md}）。
     *
     * <p><b>按优先级依次尝试</b>，第一个读到的生效：
     * <ol>
     *   <li><b>外部文件</b>：相对运行目录查找（如 {@code ../AGENTS.md}、{@code ./AGENTS.md}）
     *       —— 在服务器上改完重启即生效，**不用重新打包**</li>
     *   <li><b>classpath 内置副本</b>：打包时从仓库根目录复制进来，保证 jar 自包含、
     *       换台机器也能跑</li>
     * </ol>
     *
     * <p>两条都失败才用兜底话术 —— 那种情况下机器人虽然能跑，但没有任何人格与安全约束，
     * 所以日志打到 <b>ERROR</b> 级别，必须让人看见。
     *
     * <p><b>为什么用 {@code AGENTS.md}</b>：它是 Agent 生态的通行约定，
     * 迁移到正式 Agent 框架时可以直接复用，不用再翻译一遍。
     */
    private String loadSystemPrompt(LlmPolicy props, ResourceLoader loader) {
        String name = props.getSystemPromptFile();
        String content = null;
        String source = null;

        // ① 外部文件（优先）—— 依次尝试几个常见位置
        String[] candidates = {
                "file:./" + name,          // 运行目录（server/）
                "file:../" + name,         // 仓库根目录（本地开发、IDEA 默认）
                "file:../../" + name,      // 再上一层
        };
        for (String location : candidates) {
            try {
                Resource r = loader.getResource(location);
                if (r.exists()) {
                    content = r.getContentAsString(StandardCharsets.UTF_8);
                    source = location + "（外部文件，改完重启即生效）";
                    break;
                }
            } catch (Exception ignored) {
                // 试下一个位置
            }
        }

        // ② classpath 内置副本（打包时复制进来）
        if (content == null) {
            try {
                Resource r = loader.getResource("classpath:" + name);
                if (r.exists()) {
                    content = r.getContentAsString(StandardCharsets.UTF_8);
                    source = "classpath:" + name + "（打包内置副本）";
                }
            } catch (Exception ignored) {
                // 落到 ③
            }
        }

        // ③ 兜底：两条都没有
        if (content == null || content.isBlank()) {
            log.error("""
                    [LLM] ⚠️ 读不到系统提示词 {}！
                      已尝试：外部文件 ./ ../ ../.. 和 classpath
                      → 机器人将以【无人格、无安全约束】的默认模式运行
                      → 请在仓库根目录放一份 AGENTS.md，或重新执行 ./scripts/build-web.sh 打包""", name);
            return "你是一个 QQ 群里的聊天助手，回答要简洁、友好、口语化。";
        }

        checkPlaceholders(content, source);
        log.info("[LLM] 已加载系统提示词：{}（{} 字）", source, content.length());
        return content;
    }

    /**
     * 检查提示词里有没有**没被替换的占位符**。
     *
     * <p>为什么需要这个检查：提示词是纯文本文件，如果里面写了 §{机器人名称}§
     * 这类占位符，**没有任何代码会替换它** —— 模型看到的就是字面的花括号，
     * 行为会变得很奇怪（比如自称「{机器人名称}」），而且很难发现。
     *
     * <p>实测踩过这个坑：合并提示词时把「机器人名称」误还原成了 §{机器人名称}§，
     * 单元测试全过，因为没人检查提示词内容。
     *
     * <p>只告警不阻断：提示词有问题不该让机器人起不来。
     */
    private static final java.util.regex.Pattern UNFILLED_PLACEHOLDER =
            java.util.regex.Pattern.compile("\\{[\\u4e00-\\u9fa5A-Za-z_][\\u4e00-\\u9fa5A-Za-z0-9_]*\\}");

    private void checkPlaceholders(String content, String source) {
        var m = UNFILLED_PLACEHOLDER.matcher(content);
        java.util.Set<String> found = new java.util.LinkedHashSet<>();
        while (m.find()) {
            found.add(m.group());
        }
        if (!found.isEmpty()) {
            log.warn("""
                    [LLM] ⚠️ 提示词里有【未被替换的占位符】：{}
                      来源：{}
                      这些花括号会被原样送给模型，行为可能异常。
                      请检查 AGENTS.md，把它们改成实际内容（或加转义）。
                      注：<<<KNOWLEDGE>>> 这类标记是正常的，不在检查范围内""",
                    String.join(" ", found), source);
        }
    }

    /** 有没有可用的模型 */
    public boolean isAvailable() {
        return !models.isEmpty();
    }

    /** 有没有能看图的模型 */
    public boolean hasVision() {
        return models.keySet().stream().anyMatch(n -> configs.get(n).hasCapability(CAP_VISION));
    }

    /**
     * 纯文本对话（带降级）。
     *
     * @param userMessage 用户这一轮的内容（不要把系统提示词塞进来）
     */
    public String chat(String userMessage) {
        return callWithFallback(
                List.of(SystemMessage.from(systemPromptWithStyle()), new UserMessage(userMessage)), null);
    }

    /**
     * 本轮实际用的系统消息 = AGENTS.md（启动时读一次）+ 后台配的回复风格（每轮现取）。
     *
     * <p>风格是**追加**的，永远不可能覆盖或削弱上面的安全规则 —— 风格段开头也写明了
     * 「冲突时以安全规则为准」。
     */
    private String systemPromptWithStyle() {
        String style = describeReplyStyle(props.getReplyStyle());
        return style.isEmpty() ? systemPrompt : systemPrompt + "\n\n" + style;
    }

    /**
     * 把回复风格配置翻成给模型看的一段要求。
     *
     * @return 一项都没配、或总开关关着时返回空串（调用方就按原来的行为走）
     */
    static String describeReplyStyle(ReplyStyle s) {
        if (s == null || !s.isEnabled()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("【本轮回复风格要求】（后台配置，随时可能变；与上面任何安全规则冲突时，以安全规则为准）\n");
        if (s.getMaxChars() > 0) {
            sb.append("- 长度：正文控制在 ").append(s.getMaxChars()).append(" 字以内，宁可精简也不要啰嗦\n");
        }
        String fmt = s.getFormat() == null ? "" : s.getFormat().trim();
        if ("plain".equalsIgnoreCase(fmt)) {
            sb.append("- 格式：不要用 Markdown 的列表、小标题、表格，直接用自然的短句\n");
        } else if ("list".equalsIgnoreCase(fmt)) {
            sb.append("- 格式：要点较多时用短列表分条列，比塞成一整段更好读\n");
        }
        if (s.isIncludeSources()) {
            sb.append("- 来源：如果这一轮用了下面的检索资料，在结尾单独一行附上来源链接\n");
        }
        if (StringUtils.hasText(s.getTone())) {
            sb.append("- 语气：").append(s.getTone().trim()).append('\n');
        }
        // 只有标题、一条规则都没有 → 不插入，避免给模型一句空要求
        return sb.indexOf("- ") < 0 ? "" : sb.toString().strip();
    }

    /**
     * 带图片的对话（只在支持 vision 的 provider 里挑）。
     *
     * @param images base64 编码的图片
     */
    public String chatWithImages(String userMessage, List<VisionImage> images) {
        // ⚠️ 坑：UserMessage(String, Content...) 的第一个参数是 name 不是文本！
        // 传错会得到 "name is not supported by this endpoint" 这种莫名其妙的报错。
        // 正确做法是用 from(List<Content>) 自己组装。
        List<Content> contents = new ArrayList<>();
        contents.add(TextContent.from(userMessage));
        for (VisionImage image : images) {
            // 中性类型 -> langchain4j 的转换**只在这一层**发生。
            // 让业务包 import ImageContent 就等于把"怎么调模型"的细节漏了出去。
            contents.add(ImageContent.from(image.base64(), image.mimeType()));
        }
        ChatMessage message = UserMessage.from(contents);
        return callWithFallback(List.of(SystemMessage.from(systemPromptWithStyle()), message), CAP_VISION);
    }

    /**
     * 统一的调用入口：按 chain 顺序尝试，谁先成功用谁。
     *
     * @param requiredCapability 需要的模型能力（null = 任意）
     */
    private String callWithFallback(List<ChatMessage> messages, String requiredCapability) {
        List<String> candidates = new ArrayList<>();
        for (String name : chain) {
            if (requiredCapability == null || configs.get(name).hasCapability(requiredCapability)) {
                candidates.add(name);
            }
        }
        if (candidates.isEmpty()) {
            throw new LlmException(requiredCapability == null
                    ? "没有配置任何可用的大模型 provider"
                    : "没有支持「" + requiredCapability + "」能力的模型（图片理解需要它）");
        }

        List<String> errors = new ArrayList<>();
        for (String name : candidates) {
            ChatModel model = models.get(name);
            try {
                long start = System.currentTimeMillis();
                ChatResponse response = model.chat(messages);
                long cost = System.currentTimeMillis() - start;
                String text = response.aiMessage().text();
                // 空回复必须当成失败！推理模型（如 deepseek-v4.1-flash）在预算不够时
                // 会把 token 全花在「思考」上，content 返回空字符串。
                // 直接发出去就是一条空白消息，用户只会觉得机器人坏了。
                if (text == null || text.isBlank()) {
                    log.warn("[LLM] {} ({}) 返回了空内容（推理模型可能把预算花在思考上了），尝试下一个 provider",
                            name, configs.get(name).getModelName());
                    errors.add(name + " -> 空回复");
                    continue;
                }
                log.info("[LLM] {} ({}) 回复成功，耗时 {} ms，{} 字",
                        name, configs.get(name).getModelName(), cost, text.length());
                // 用量只有这一层拿得到（langchain4j 的 ChatResponse 里带着），
                // 交给成本预算累加，供「今日 token 额度」判定用
                if (response.tokenUsage() != null) {
                    budgetGuard.recordTokens(response.tokenUsage().totalTokenCount());
                }
                return text;
            } catch (Exception e) {
                log.warn("[LLM] {} 调用失败，尝试下一个：{}", name, e.getMessage());
                errors.add(name + " -> " + e.getMessage());
            }
        }
        throw new LlmException("所有大模型都调用失败了：" + String.join("; ", errors));
    }
}
