package com.example.qqbot.guard.stage;

import com.example.qqbot.config.GuardProperties;
import com.example.qqbot.guard.GuardContext;
import com.example.qqbot.guard.GuardResult;
import com.example.qqbot.guard.GuardStage;
import com.example.qqbot.guard.WordList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

/**
 * 入站关键词拦截。
 *
 * <p><b>关键点：命中后直接返回拒绝话术，不调用大模型。</b>
 * 位置很讲究 —— 放在调模型之前，攻击者才不能用敏感内容/注入话术烧你的 token。
 *
 * <p>词表在配置文件里维护，见 resources/words/inbound.txt。
 *
 * <p><b>回复里可以带上命中的词</b>：拒绝话术模板里写 {word} 就会被替换成实际命中的词。
 * 这是为了排查误判 —— 把 {word} 从模板里删掉就恢复成不带词的回复。
 */
@Component
public class InboundWordStage implements GuardStage {

    private static final Logger log = LoggerFactory.getLogger(InboundWordStage.class);

    private final GuardProperties properties;
    private final WordList wordList;

    public InboundWordStage(GuardProperties properties, ResourceLoader resourceLoader) {
        this.properties = properties;
        this.wordList = WordList.load(resourceLoader, properties.getWords().getInboundFile());
    }

    @Override
    public String name() {
        return "inbound-words";
    }

    @Override
    public GuardResult check(GuardContext ctx) {
        if (!properties.isEnabled() || !properties.getWords().isEnabled()) {
            return GuardResult.pass();
        }
        return wordList.firstMatch(ctx.text())
                .map(hit -> {
                    // 只记录命中的词，不记录用户原文（避免把敏感内容写进日志）
                    // 日志里给出词表位置，方便定位误判
                    log.warn("[GUARD] 入站命中词表「{}」→ 已拒绝（未调用大模型）。"
                                    + "如果是误判，去 {} 里删掉这一行",
                            hit, properties.getWords().getInboundFile());
                    String reply = render(properties.getWords().getInboundRefusalText(), hit);
                    return GuardResult.reply(name(), "命中入站词表：" + hit, reply);
                })
                .orElseGet(GuardResult::pass);
    }

    /** 把模板里的 {word} 替换成实际命中的词；模板里没有占位符就原样返回 */
    private String render(String template, String word) {
        if (template == null) {
            return "";
        }
        return template.replace("{word}", word == null ? "" : word);
    }

    public int wordCount() {
        return wordList.size();
    }
}
