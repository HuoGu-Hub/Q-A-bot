package com.example.qqbot.guard.stage;

import com.example.qqbot.config.GuardProperties;
import com.example.qqbot.guard.GuardContext;
import com.example.qqbot.guard.GuardResult;
import com.example.qqbot.guard.GuardStage;
import org.springframework.stereotype.Component;

/**
 * 内容门控：消息里既没有文字、也没有图片时（纯表情 / 语音），
 * **不调用大模型**，直接用系统设定的兜底词回复。
 *
 * <p>三种情况会放行：
 * <ol>
 *   <li>有文字</li>
 *   <li>有图片（交给后面的 ChatService 判断「当前有没有能看图的模型」）</li>
 *   <li><b>有引用</b> —— 关键！用户可能「只引用不发言」，
 *       而被引用的消息里有文字或图片。这里不能提前判定为空，
 *       否则引用一张图片再 @ 机器人就会被误拦。</li>
 * </ol>
 *
 * <p>这一层在 RateLimitStage 之后，所以兜底回复同样消耗频率配额 ——
 * 否则有人连发几十个表情，机器人就会连回几十条兜底词，一样是炸群。
 */
@Component
public class ContentGateStage implements GuardStage {

    private final GuardProperties.ContentGate config;

    public ContentGateStage(GuardProperties properties) {
        this.config = properties.getContentGate();
    }

    @Override
    public String name() {
        return "content-gate";
    }

    @Override
    public GuardResult check(GuardContext ctx) {
        if (!config.isEnabled()) {
            return GuardResult.pass();
        }
        if (ctx.hasContent() || ctx.hasQuote()) {
            return GuardResult.pass();
        }
        return GuardResult.reply(name(), "消息里既没有文字也没有图片（纯表情/语音）",
                config.getNoTextReply());
    }
}
