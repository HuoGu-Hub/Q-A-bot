package com.example.qqbot.guard;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

/**
 * 出站内容过滤：模型说了不该说的话时，把整条回复换成兜底话术。
 *
 * <p>为什么出站比入站宽松：入站是「用户让我说」，可以直接拒；
 * 出站是「模型自己发挥」，词表太严会把正常回复误伤成兜底话术，体验很差。
 * 所以出站词表只放**绝对不能出现**的东西。
 *
 * <p>兜底话术模板里写 {word} 会被替换成实际命中的词，方便排查误判。
 */
@Component
public class OutboundFilter {

    private static final Logger log = LoggerFactory.getLogger(OutboundFilter.class);

    private final Words words;
    private final WordList wordList;

    public OutboundFilter(Words words, ResourceLoader resourceLoader) {
        this.words = words;
        this.wordList = WordList.load(resourceLoader, words.getOutboundFile());
    }

    /**
     * 过滤模型输出。
     *
     * @return 安全文本；命中词表时返回兜底话术
     */
    public String filter(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        if (!words.isEnabled() || !words.isEnabled()) {
            return text;
        }
        return wordList.firstMatch(text)
                .map(hit -> {
                    // 只记录命中的词，不记录模型原文（避免把敏感内容写进日志）
                    log.warn("[GUARD] 出站命中词表「{}」→ 整条回复已替换。"
                                    + "如果是误判，去 {} 里删掉这一行",
                            hit, words.getOutboundFile());
                    String template = words.getOutboundFallbackText();
                    return template == null ? "" : template.replace("{word}", hit);
                })
                .orElse(text);
    }

    public int wordCount() {
        return wordList.size();
    }
}
