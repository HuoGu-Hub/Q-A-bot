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
 * <p>TSV 四列：问题、回答、期望结论（PASS/FAIL）、备注。空行与 # 开头的行忽略。
 */
@Component
public class JudgeCalibration {

    public record Row(String question, String expected, String actual, double score,
                      boolean agreed, String reason) {
    }

    public record Report(int total, int agreed, double agreement, double passMean, double failMean,
                         double separation, List<Row> rows) {
    }

    private final RubricJudge judge;

    public JudgeCalibration(RubricJudge judge) {
        this.judge = judge;
    }

    /** 从仓库里读默认校准集；找不到就返回 null，提示调用方先放文件 */
    public Report runDefault(String provider) {
        for (String candidate : List.of("tests/golden/judge-calibration.tsv",
                "../tests/golden/judge-calibration.tsv")) {
            Path path = Paths.get(candidate);
            if (!Files.exists(path)) {
                continue;
            }
            try {
                return run(Files.readString(path, StandardCharsets.UTF_8), provider);
            } catch (IOException e) {
                return null;
            }
        }
        return null;
    }

    public Report run(String tsv, String provider) {
        List<Row> rows = new ArrayList<>();
        int agreed = 0;
        for (String line : tsv.split("\\R")) {
            String trimmed = line.strip();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            String[] cols = trimmed.split("\\t", -1);
            if (cols.length < 3) {
                continue;
            }
            String question = cols[0].strip();
            String answer = cols[1].strip();
            String expected = cols[2].strip().toUpperCase();
            if (!"PASS".equals(expected) && !"FAIL".equals(expected)) {
                continue;
            }
            // 校准固定单次采样：这里量的是判定器本身，不是采样策略带来的抖动
            RubricJudge.Opinion opinion = judge.judge(RubricJudge.RELEVANCE, question, answer, "",
                    1, "average", provider);
            if (opinion == null) {
                continue;
            }
            String actual = opinion.pass() ? "PASS" : "FAIL";
            boolean ok = expected.equals(actual);
            if (ok) {
                agreed++;
            }
            rows.add(new Row(question, expected, actual, opinion.score(), ok, opinion.reason()));
        }
        double passMean = mean(rows, "PASS");
        double failMean = mean(rows, "FAIL");
        double agreement = rows.isEmpty() ? 0 : (double) agreed / rows.size();
        return new Report(rows.size(), agreed, round(agreement), round(passMean), round(failMean),
                round(passMean - failMean), rows);
    }

    private static double mean(List<Row> rows, String verdict) {
        return rows.stream().filter(r -> verdict.equals(r.expected()))
                .mapToDouble(Row::score).average().orElse(0);
    }

    private static double round(double v) {
        return Math.round(v * 1000) / 1000.0;
    }
}
