package com.example.qqbot.eval;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 定时校准的开关语义：两个开关任一关闭都不该打模型；打开后按配置的判定器逐个跑并落库。
 */
class EvalCalibrationJobTest {

    private static MockEnvironment env(boolean calibration, boolean judge) {
        return new MockEnvironment()
                .withProperty("app.eval.calibration.enabled", String.valueOf(calibration))
                .withProperty("app.eval.judge.enabled", String.valueOf(judge))
                .withProperty("app.eval.calibration.graders", "relevance,groundedness")
                .withProperty("app.eval.judge.provider", "");
    }

    @Test
    @DisplayName("判定开关没开就不校准，省 token")
    void skipsWhenJudgeDisabled() {
        JudgeCalibration calibration = mock(JudgeCalibration.class);
        EvalService service = mock(EvalService.class);
        new EvalCalibrationJob(calibration, service, env(true, false)).run();
        verify(calibration, never()).runDefault(anyString(), anyString());
    }

    @Test
    @DisplayName("打开后按配置逐个判定器校准并落库")
    void runsConfiguredGraders() {
        JudgeCalibration calibration = mock(JudgeCalibration.class);
        EvalService service = mock(EvalService.class);
        when(calibration.runDefault(eq("relevance"), anyString()))
                .thenReturn(new JudgeCalibration.Report("relevance", 10, 8, 0.8, 4.2, 1.9, 2.3,
                        List.of()));
        when(calibration.runDefault(eq("groundedness"), anyString()))
                .thenReturn(new JudgeCalibration.Report("groundedness", 6, 5, 0.83, 4.0, 2.0, 2.0,
                        List.of()));

        new EvalCalibrationJob(calibration, service, env(true, true)).run();

        verify(calibration).runDefault("relevance", "");
        verify(calibration).runDefault("groundedness", "");
        verify(service, times(2)).saveCalibration(any(JudgeCalibration.Report.class), anyString());
    }

    @Test
    @DisplayName("没有样本的判定器不落库")
    void skipsEmptyReport() {
        JudgeCalibration calibration = mock(JudgeCalibration.class);
        EvalService service = mock(EvalService.class);
        when(calibration.runDefault(anyString(), anyString()))
                .thenReturn(new JudgeCalibration.Report("relevance", 0, 0, 0, 0, 0, 0, List.of()));

        new EvalCalibrationJob(calibration, service, env(true, true)).run();

        verify(service, never()).saveCalibration(any(), anyString());
    }
}
