package com.example.qqbot.eval;

import com.example.qqbot.trace.ToolCallTrace;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Agent 维度的代码判定：同一工具用同样参数被反复调用，就是循环。
 *
 * <p>这是零成本判定，不调模型。阈值取 3 次：两次可能是合理的重试，
 * 三次以上基本是模型在原地打转。参数按原文比较，不做语义归一。
 */
public final class AgentLoopJudge {

    private static final int LOOP_THRESHOLD = 3;

    private AgentLoopJudge() {
    }

    /** @return 没有工具调用时返回 null，有这个维度没东西可判 */
    public static Judgement judge(List<ToolCallTrace.Call> calls) {
        if (calls == null || calls.isEmpty()) {
            return null;
        }
        Map<String, Long> repeats = calls.stream()
                .filter(c -> !"denied".equals(c.status()))
                .collect(Collectors.groupingBy(c -> c.name() + "|" + c.args(),
                        Collectors.counting()));
        Map.Entry<String, Long> worst = repeats.entrySet().stream()
                .max(Map.Entry.comparingByValue()).orElse(null);
        if (worst != null && worst.getValue() >= LOOP_THRESHOLD) {
            String tool = worst.getKey().split("\\|", 2)[0];
            return new Judgement("agent", Judgement.FAIL, 0,
                    "工具 " + tool + " 用相同参数调用了 " + worst.getValue() + " 次",
                    "AGENT_LOOP", Map.of("tool", tool, "repeats", worst.getValue(),
                    "calls", calls.size()));
        }
        return new Judgement("agent", Judgement.PASS, 1, "没有重复调用", null,
                Map.of("calls", calls.size(), "distinct", repeats.size()));
    }
}
