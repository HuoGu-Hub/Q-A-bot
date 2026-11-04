package com.example.qqbot.eval;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * 判定器校准 —— 用人工标好的样本量出"判分到底准不准"。
 *
 * <p>没有这一层，rubric 判定只是一段看起来合理的 prompt：分数进了通过率，
 * 却没人知道它与人的判断差多远。这里算两个数：
 * <ul>
 *   <li>一致率：判定的通过与否和人工标注相同的比例</li>
 *   <li>分档差：人工判 pass 的样本平均得分，与判 fail 的样本平均得分，差得越开越好</li>
 * </ul>
 *
 * <p>TSV 五列：判定器、问题、回答、上下文、期望结论（PASS/FAIL）。
 * 上下文对忠实度是必填，对相关性留空即可；工具选择的上下文写调用清单。
 */
@Component
public class JudgeCalibration {

    public record Row(String grader, String question, String expected, String actual, double score,
                      boolean agreed, String reason) {
    }

    public record Report(String grader, int total, int agreed, double agreement, double passMean,
                         double failMean, double separation, List<Row> rows) {
    }

    private final RubricJudge judge;

    public JudgeCalibration(RubricJudge judge) {
        this.judge = judge;
    }

    public Report runDefault(String graderId, String provider) {
        for (String candidate : List.of("tests/golden/judge-calibration.tsv",
                "../tests/golden/judge-calibration.tsv")) {
            Path path = Paths.get(candidate);
            if (!Files.exists(path)) {
                continue;
            }
            try {
                return run(Files.readString(path, StandardCharsets.UTF_8), graderId, provider);
            } catch (IOException e) {
                return null;
            }
        }
        return null;
    }

    public Report run(String tsv, String graderId, String provider) {
        RubricJudge.Grader grader = RubricJudge.graderById(
                graderId == null || graderId.isBlank() ? "relevance" : graderId.strip());
        if (grader == null) {
            return null;
        }
        List<Row> rows = new ArrayList<>();
        int agreed = 0;
        for (String line : tsv.split("\\R")) {
            String trimmed = line.strip();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            String[] cols = trimmed.split("\\t", -1);
            if (cols.length < 5 || !cols[0].strip().equals(grader.id())) {
                continue;
            }
            String question = cols[1].strip();
            String answer = cols[2].strip();
            String context = cols[3].strip();
            String expected = cols[4].strip().toUpperCase();
            if (!"PASS".equals(expected) && !"FAIL".equals(expected)) {
                continue;
            }
            // 校准固定单次采样：这里量的是判定器本身，不是采样策略带来的抖动。
            // 工具选择的输入形态不同，直接把调用清单拼成用户消息。
            RubricJudge.Opinion opinion = "tool-selection".equals(grader.id())
                    ? judge.judgeRaw(grader, "问题：" + question + "\n工具调用：\n" + context,
                            1, "average", provider)
                    : judge.judge(grader, question, answer, context, 1, "average", provider);
            if (opinion == null) {
                continue;
            }
            String actual = opinion.pass() ? "PASS" : "FAIL";
            boolean ok = expected.equals(actual);
            if (ok) {
                agreed++;
            }
            rows.add(new Row(grader.id(), question, expected, actual, opinion.score(), ok,
                    opinion.reason()));
        }
        double passMean = mean(rows, "PASS");
        double failMean = mean(rows, "FAIL");
        double agreement = rows.isEmpty() ? 0 : (double) agreed / rows.size();
        return new Report(grader.id(), rows.size(), agreed, round(agreement), round(passMean),
                round(failMean), round(passMean - failMean), rows);
    }

    private static double mean(List<Row> rows, String verdict) {
        return rows.stream().filter(r -> verdict.equals(r.expected()))
                .mapToDouble(Row::score).average().orElse(0);
    }

    private static double round(double v) {
        return Math.round(v * 1000) / 1000.0;
    }
}
