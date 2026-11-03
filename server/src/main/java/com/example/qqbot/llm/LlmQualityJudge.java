package com.example.qqbot.llm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 用模型给回答打质量分 —— 补规则判定看不到的"事实与是否答到点上"。
 *
 * <p>放在 llm 包是因为它是唯一允许碰模型 SDK 的地方；它不认识评测的
 * {@code Judgement}，只回一个中性的意见，由评测侧决定怎么落库。
 *
 * <p>输出格式故意定得很死：{@code SCORE: 0.8} 与 {@code REASON: ...}。
 * 解析失败宁可返回 null 让调用方退回规则判定，也不猜一个分数 —— 猜出来的分数
 * 会进通过率，比没有更糟。
 */
@Component
public class LlmQualityJudge {

    private static final Logger log = LoggerFactory.getLogger(LlmQualityJudge.class);
    private static final Pattern SCORE = Pattern.compile("SCORE\\s*[:：]\\s*(0(?:\\.\\d+)?|1(?:\\.0+)?)");
    private static final String SYSTEM = """
            你是回答质量评审。只输出两行，不要解释其他内容：
            SCORE: 0 到 1 之间的小数
            REASON: 一句话说明扣分点；满分写"准确且切题"
            评分只看：是否回答了问题、事实是否与给定资料一致、有没有编造资料里没有的信息。
            """;

    private final LlmRouter router;

    public LlmQualityJudge(LlmRouter router) {
        this.router = router;
    }

    /** @return null 表示这次判定不可用，调用方应退回规则判定 */
    public Opinion judge(String question, String answer, List<String> evidenceTitles, String sources) {
        if (answer == null || answer.isBlank()) {
            return null;
        }
        try {
            String user = "问题：" + question
                    + "\n资料标题：" + (evidenceTitles.isEmpty() ? "无" : String.join("、", evidenceTitles))
                    + "\n资料出处：" + (sources == null || sources.isBlank() ? "无" : sources)
                    + "\n回答：" + truncate(answer, 2000);
            LlmRouter.Reply reply = router.chatWithSystem(SYSTEM, user);
            Opinion parsed = parse(reply.text(), reply.model(), reply.tokensIn(), reply.tokensOut());
            if (parsed == null) {
                log.warn("[EVAL] LLM 判定输出无法解析：{}", truncate(reply.text(), 120));
            }
            return parsed;
        } catch (RuntimeException e) {
            log.warn("[EVAL] LLM 判定调用失败：{}", e.getMessage());
            return null;
        }
    }

    public record Opinion(double score, boolean pass, String reason, String model,
                          long tokensIn, long tokensOut) {
    }

    static Opinion parse(String text, String model, long tokensIn, long tokensOut) {
        if (text == null) {
            return null;
        }
        Matcher m = SCORE.matcher(text);
        if (!m.find()) {
            return null;
        }
        double score = Double.parseDouble(m.group(1));
        String reason = "";
        for (String line : text.split("\\R")) {
            if (line.strip().startsWith("REASON")) {
                reason = line.substring(line.indexOf(':') + 1).strip();
                if (reason.isEmpty()) {
                    reason = line.substring(line.indexOf('：') + 1).strip();
                }
            }
        }
        return new Opinion(score, score >= 0.6, reason.isBlank() ? "模型未给出理由" : reason,
                model, tokensIn, tokensOut);
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
