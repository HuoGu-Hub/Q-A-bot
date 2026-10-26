package com.example.qqbot.llm;

import com.example.qqbot.kb.ToolBudget;
import com.example.qqbot.voice.MediaOutbox;
import com.example.qqbot.voice.SpeechSynthesizer;
import com.example.qqbot.voice.VoiceAsset;
import com.example.qqbot.voice.VoiceException;
import com.example.qqbot.voice.VoiceLibrary;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.invocation.InvocationContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Optional;

/**
 * 给模型的语音工具 —— {@code play_voice} 与 {@code say}。
 *
 * <h2>为什么放在 llm 包</h2>
 * 工具是模型协议的入口，ArchUnit 只允许 llm 与 kb 碰 langchain4j；
 * 业务实现（素材库、合成器）仍在 voice 包里，这里只负责"翻译"。
 *
 * <h2>为什么工具不直接发送</h2>
 * 它只往 {@link MediaOutbox} 记一条。真正发送在文字生成完之后由路由决定顺序，
 * 见 {@code MediaOutbox} 的类注释。
 *
 * <h2>为什么只读素材库</h2>
 * 参数是名字/标签，不是路径。模型就算被提示词诱导，也读不到服务器上的任意文件。
 */
@Component
public class VoiceTool {

    private static final Logger log = LoggerFactory.getLogger(VoiceTool.class);

    /** 现场合成最多允许多少字。再长就不像聊天了 */
    static final int MAX_SPEECH_CHARS = 80;

    private final VoiceLibrary library;
    private final SpeechSynthesizer synthesizer;

    public VoiceTool(VoiceLibrary library, SpeechSynthesizer synthesizer) {
        this.library = library;
        this.synthesizer = synthesizer;
    }

    /**
     * 播一段现成的素材。
     *
     * <p>只给"用户明确想听点东西"的场合用：点歌、要猫叫、要一段音效。
     * 普通的问答不要调它 —— 语音条比文字慢，而且在群里更打扰。
     */
    @Tool(name = "play_voice", value = "从语音素材库挑一段音频发到当前聊天。"
            + "query 可以是曲名、别名或标签，例如「晴天」「周杰伦」「猫叫」。"
            + "找不到时会返回提示，不要重复调用；可以改用 say 现场说一句。"
            + "只在用户明确想听声音时使用，普通问答请直接用文字。")
    public String playVoice(@P("曲名、别名或标签") String query, InvocationContext ctx) {
        return playVoice(query, outboxOf(ctx), ToolBudget.of(ctx));
    }

    /** 包级可见的实体：测试直接给清单和预算，不用构造框架上下文 */
    String playVoice(String query, MediaOutbox outbox, ToolBudget budget) {
        if (outbox == null) {
            return "当前场景不能发语音，请直接用文字回答。";
        }
        if (budget != null && !budget.tryAcquire()) {
            return "本次工具预算已用完（" + budget.reason() + "），请直接用文字回答。";
        }
        String q = query == null ? "" : query.trim();
        Optional<VoiceAsset> picked = library.pick(q);
        if (picked.isEmpty()) {
            return "素材库里没有匹配「" + q + "」的语音。可以改用 say 现场说一句，或者直接用文字回答。";
        }
        VoiceAsset asset = picked.get();
        if (!outbox.add(MediaOutbox.Intent.asset(asset.id(), asset.title()))) {
            return "一条回复最多两段语音，已经排满了。";
        }
        log.info("[TOOL] play_voice「{}」→ #{}《{}》", q, asset.id(), asset.title());
        return "已安排发送《" + asset.title() + "》（"
                + String.format(Locale.ROOT, "%.1f", asset.durationMs() / 1000.0)
                + " 秒）。回答里不用再解释自己能不能发语音。";
    }

    /**
     * 现场合成一句话。
     *
     * <p>适合"没有现成素材、但这句话用说的更有味道"的场合：
     * 晚安、鼓励、短句回应。不要拿它念整段回答。
     */
    @Tool(name = "say", value = "让机器人现场说一句话，用 TTS 合成语音条。"
            + "适合没有现成素材、但一句话用说的更自然时，例如「晚安」「干得漂亮」。"
            + "文字不超过 80 字；不要用它朗读整段回答。")
    public String say(@P("要说的话，不超过 80 字") String text, InvocationContext ctx) {
        return say(text, outboxOf(ctx), ToolBudget.of(ctx));
    }

    /** 包级可见的实体：测试直接给清单和预算 */
    String say(String text, MediaOutbox outbox, ToolBudget budget) {
        if (outbox == null) {
            return "当前场景不能发语音，请直接用文字回答。";
        }
        if (budget != null && !budget.tryAcquire()) {
            return "本次工具预算已用完（" + budget.reason() + "），请直接用文字回答。";
        }
        if (!synthesizer.isAvailable()) {
            return "语音合成暂时不可用，请直接用文字回答。";
        }
        String spoken = text == null ? "" : text.trim();
        if (spoken.isEmpty()) {
            return "没有要说的内容。";
        }
        if (spoken.length() > MAX_SPEECH_CHARS) {
            spoken = spoken.substring(0, MAX_SPEECH_CHARS);
        }
        if (!outbox.add(MediaOutbox.Intent.speech(spoken))) {
            return "一条回复最多两段语音，已经排满了。";
        }
        log.info("[TOOL] say {} 字：{}", spoken.length(), spoken.length() > 30 ? spoken.substring(0, 30) + "…" : spoken);
        return "已安排现场合成这句话（" + spoken.length() + " 字），会在回答时发出。";
    }

    private static MediaOutbox outboxOf(InvocationContext ctx) {
        if (ctx == null || ctx.invocationParameters() == null) {
            return null;
        }
        Object value = ctx.invocationParameters().get(MediaOutbox.KEY);
        return value instanceof MediaOutbox outbox ? outbox : null;
    }
}
