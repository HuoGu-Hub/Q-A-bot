package com.example.qqbot;

import com.example.qqbot.eval.EvalViews;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 门禁退出码是流水线唯一的契约：0 通过、1 警告、2 阻断、3 无法判定。
 * 这里只测判定本身，开库与读表是另一层的事。
 */
class EvalGateCliTest {

    private static EvalViews.Summary summary(int pass, int fail, int skip) {
        double rate = pass + fail == 0 ? 0 : (double) pass / (pass + fail);
        return new EvalViews.Summary(pass + fail + skip, pass + fail, pass, fail, skip, rate,
                800, 1200, 0, 0, 0, 0, List.of(), List.of());
    }

    @Test
    @DisplayName("没有基线时按默认阈值：低于 80% 给警告，不阻断")
    void defaultThreshold() {
        EvalGateCli.Decision warn = EvalGateCli.decide(summary(7, 3, 0), null);
        assertThat(warn.status()).isEqualTo("WARN");
        assertThat(warn.exitCode()).isEqualTo(1);

        EvalGateCli.Decision pass = EvalGateCli.decide(summary(9, 1, 0), null);
        assertThat(pass.status()).isEqualTo("PASS");
        assertThat(pass.exitCode()).isZero();
    }

    @Test
    @DisplayName("有基线时回退超过 3 个百分点判阻断")
    void baselineRegressionBlocks() {
        EvalGateCli.Decision blocked = EvalGateCli.decide(summary(8, 2, 0), 0.90);
        assertThat(blocked.status()).isEqualTo("BLOCKED");
        assertThat(blocked.exitCode()).isEqualTo(2);

        EvalGateCli.Decision ok = EvalGateCli.decide(summary(9, 1, 0), 0.90);
        assertThat(ok.status()).isEqualTo("PASS");
        assertThat(ok.exitCode()).isZero();
    }

    @Test
    @DisplayName("没有判定结果时无法判定，退出码 3")
    void unknownWhenNothingJudged() {
        EvalGateCli.Decision unknown = EvalGateCli.decide(summary(0, 0, 5), 0.9);
        assertThat(unknown.status()).isEqualTo("UNKNOWN");
        assertThat(unknown.exitCode()).isEqualTo(3);
    }
}
