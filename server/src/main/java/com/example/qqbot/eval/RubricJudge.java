package com.example.qqbot.eval;

import com.example.qqbot.llm.JudgeModel;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * rubric 判定器 —— 用 1-5 分的评分标准代替 0-1 的"过没过"，并按策略多次采样。
 *
 * <p>借鉴 OpenJudge 的三点：评分标准写成 rubric 而不是一句"打分"；分数用 1-5 的档位；
 * 同一判定跑多次，按均值或投票收敛。判定器用哪个模型由调用方指定，和被测模型解耦。
 *
 * <p>解析失败返回 null，调用方退回规则结论。猜一个分数进通过率，比没有判定更糟。
 */
@Component
public class RubricJudge {

    private static final Logger log = LoggerFactory.getLogger(RubricJudge.class);

    /**
     * 判定器定义。版本不是一个手写数字，而是 rubric 文本的哈希 ——
     * 改一个字版本就会变，不会出现"改了 prompt 忘了升版本，历史被误读"。
     */
    public record Grader(String id, String dimension, double threshold, String systemPrompt) {

        public int version() {
            return 1 + Math.floorMod((id + '\u0000' + systemPrompt).hashCode(), 100_000);
        }
    }

    /** 一个判定器的结论：分数、是否通过、逐次采样明细 */
    public record Opinion(double score, boolean pass, String reason, int samples,
                          List<Double> rawScores, String strategy, String graderId,
                          int graderVersion) {
    }

    private final JudgeModel model;
    private final ObjectMapper mapper;

    public RubricJudge(JudgeModel model, ObjectMapper mapper) {
        this.model = model;
        this.mapper = mapper;
    }

    /** 相关性：回答是否真正回答了问题 */
    public static final Grader RELEVANCE = new Grader("relevance", "quality", 3.5, """
            你是回答质量评审，按 1 到 5 分给助手回答打分。
            5 分：直接、完整地回答了问题，信息准确，没有多余的空话
            4 分：回答了问题，但缺少一个次要细节
            3 分：部分相关，核心问题没答到
            2 分：只沾边，几乎没有可用信息
            1 分：答非所问、空话或拒绝回答
            只输出一行 JSON：{"score": 4, "reason": "一句话说明扣分点"}
            """);

    /** 忠实度：回答里的信息是否来自给定资料，有没有编造 */
    public static final Grader GROUNDEDNESS = new Grader("groundedness", "quality", 3.5, """
            你是事实核查评审。给你检索到的资料标题与来源、用户问题和助手回答。
            5 分：回答里的关键信息都能在资料里找到依据
            4 分：大部分有依据，个别细节资料里没有但不算编造
            3 分：一半左右有依据，存在资料里没有的推测
            2 分：多数内容资料里没有
            1 分：明显编造，或与资料矛盾
            没有资料时按 3 分处理，不要凭空判断。
            只输出一行 JSON：{"score": 4, "reason": "一句话说明依据"}
            """);

    /** 工具选择：调用的工具与参数是否适合这个问题，只对有工具调用的 run 判 */
    public static final Grader TOOL_SELECTION = new Grader("tool-selection", "agent", 3.5, """
            你是工具调用评审。给你用户问题和助手实际调用的工具与参数。
            5 分：工具选得对，参数完整，足以支撑回答
            4 分：工具对，参数有小瑕疵，但不影响结果
            3 分：工具勉强相关，但看不出必要性
            2 分：工具与问题基本无关，或有明显多余调用
            1 分：完全没有调用该调的工具，或者调用了错误的工具
            只输出一行 JSON：{"score": 4, "reason": "一句话说明理由"}
            """);

    public List<Grader> graders() {
        return List.of(RELEVANCE, GROUNDEDNESS, TOOL_SELECTION);
    }

    /** 按判定器 id 找内置 grader，找不到返回 null */
    public static Grader graderById(String id) {
        for (Grader g : List.of(RELEVANCE, GROUNDEDNESS, TOOL_SELECTION)) {
            if (g.id().equals(id)) {
                return g;
            }
        }
        return null;
    }

    /**
     * @param samples  采样次数，1 表示不重复
     * @param strategy average 取均值，vote 取多数票
     * @return null 表示这次判定不可用，调用方退回规则判定
     */
    public Opinion judge(Grader grader, String question, String answer, String context,
                         int samples, String strategy, String provider) {
        if (answer == null || answer.isBlank()) {
            return null;
        }
        return judgeRaw(grader, buildUser(question, answer, context), samples, strategy, provider);
    }

    /** 直接给完整用户消息，工具选择这类输入形态不一样的判定器用这个 */
    public Opinion judgeRaw(Grader grader, String user, int samples, String strategy,
                            String provider) {
        int n = Math.max(1, Math.min(5, samples));
        List<Double> scores = new ArrayList<>();
        List<String> reasons = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            try {
                String raw = model.ask(grader.systemPrompt(), user, provider);
                Parsed parsed = parse(raw);
                if (parsed == null) {
                    log.warn("[EVAL] {} 判定输出无法解析，第 {} 次采样丢弃：{}", grader.id(), i + 1,
                            truncate(raw, 120));
                    continue;
                }
                scores.add(parsed.score());
                reasons.add(parsed.reason());
            } catch (RuntimeException e) {
                log.warn("[EVAL] {} 判定调用失败，第 {} 次：{}", grader.id(), i + 1, e.getMessage());
            }
        }
        if (scores.isEmpty()) {
            return null;
        }
        double mean = scores.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        boolean pass = "vote".equalsIgnoreCase(strategy)
                ? scores.stream().filter(s -> s >= grader.threshold()).count() * 2 > scores.size()
                : mean >= grader.threshold();
        return new Opinion(round(mean), pass, reasons.get(0), scores.size(), List.copyOf(scores),
                strategy == null ? "average" : strategy, grader.id(), grader.version());
    }

    private String buildUser(String question, String answer, String context) {
        StringBuilder sb = new StringBuilder();
        sb.append("问题：").append(question == null ? "" : question).append('\n');
        if (context != null && !context.isBlank()) {
            sb.append("资料：").append(truncate(context, 1200)).append('\n');
        }
        sb.append("回答：").append(truncate(answer, 2000));
        return sb.toString();
    }

    record Parsed(double score, String reason) {
    }

    /** 从模型输出里找第一个 JSON 对象；找不到再退回正则取 score */
    Parsed parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        if (start >= 0 && end > start) {
            try {
                JsonNode node = mapper.readTree(raw.substring(start, end + 1));
                JsonNode score = node.get("score");
                if (score != null && score.isNumber()) {
                    double v = score.asDouble();
                    if (v >= 1 && v <= 5) {
                        String reason = node.hasNonNull("reason") ? node.get("reason").asText() : "";
                        return new Parsed(v, reason.isBlank() ? "模型未给出理由" : reason);
                    }
                }
            } catch (Exception ignored) {
                // 落到正则兜底
            }
        }
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\"?score\"?\\s*[:：]\\s*([1-5](?:\\.\\d+)?)",
                        java.util.regex.Pattern.CASE_INSENSITIVE).matcher(raw);
        if (m.find()) {
            return new Parsed(Double.parseDouble(m.group(1)), "仅有分数，没有理由");
        }
        return null;
    }

    private static double round(double v) {
        return Math.round(v * 100) / 100.0;
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    /** 供校准与页面复用：按维度映射证据 */
    public Map<String, Object> evidence(Opinion o, String question, String answer) {
        Map<String, Object> ev = new LinkedHashMap<>();
        ev.put("grader", o.graderId());
        ev.put("graderVersion", o.graderVersion());
        ev.put("strategy", o.strategy());
        ev.put("samples", o.samples());
        ev.put("rawScores", o.rawScores());
        return ev;
    }
}
