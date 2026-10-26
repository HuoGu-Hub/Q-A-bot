package com.example.qqbot.voice;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 真实 ffmpeg 转码测试。
 *
 * <p>为什么值得跑真的：滤镜链拼错、参数写错时，最典型的表现不是报错，
 * 而是**发出去没声音**或者时长不对 —— 这种问题只有在真机上转一遍才看得见。
 *
 * <p>本机没有 ffmpeg 时整类跳过（{@link org.junit.jupiter.api.Assumptions}），
 * 不让 CI 因为环境缺工具而红。
 */
class VoiceProcessorTest {

    private VoiceProcessor processor;

    @BeforeEach
    void setUp() {
        assumeTrue(ffmpegAvailable(), "本机没有 ffmpeg，跳过真实转码测试");
        processor = new VoiceProcessor(new VoicePolicy() {
            @Override
            public boolean isEnabled() {
                return true;
            }

            @Override
            public int getMaxKb() {
                return 1024;
            }

            @Override
            public int getMaxSeconds() {
                return 300;
            }

            @Override
            public String getFfmpeg() {
                return "ffmpeg";
            }

            @Override
            public String getFfprobe() {
                return "ffprobe";
            }

            @Override
            public int getTimeoutSeconds() {
                return 30;
            }
        });
    }

    @Test
    @DisplayName("2 秒正弦波 → mp3，时长量得出来")
    void encodesWavToMp3() throws Exception {
        byte[] wav = sineWav(2.0);

        VoiceProcessor.Encoded encoded = processor.encode(wav, "wav", VoiceOptions.NONE);

        assertThat(encoded.audio()).isNotEmpty();
        assertThat(encoded.audio().length).isLessThan(wav.length);
        // 24kHz 单声道 32kbps 的 mp3，2 秒大约 8KB
        assertThat(encoded.audio().length).isGreaterThan(2000);
        assertThat(encoded.seconds()).isBetween(1.5, 2.6);
    }

    @Test
    @DisplayName("裁 1 秒：输出时长跟着变，不是只改了元数据")
    void trimsAudio() throws Exception {
        VoiceProcessor.Encoded encoded = processor.encode(sineWav(2.0), "wav",
                VoiceOptions.builder().start(0.5).duration(1.0).build());

        assertThat(encoded.seconds()).isBetween(0.7, 1.4);
    }

    @Test
    @DisplayName("升 12 个半音：时长基本不变，说明 atempo 把变调带来的变速抵消了")
    void pitchShiftKeepsDuration() throws Exception {
        VoiceProcessor.Encoded encoded = processor.encode(sineWav(2.0), "wav",
                VoiceOptions.builder().semitones(12).build());

        // 只改频率不改时长；留一点余量给编码器首尾
        assertThat(encoded.seconds()).isBetween(1.5, 2.6);
    }

    /** 用 ffmpeg 的 lavfi 现场造一个测试音频，不往仓库里放二进制样本 */
    private static byte[] sineWav(double seconds) throws IOException, InterruptedException {
        Path dir = Files.createTempDirectory("voice-test-");
        Path out = dir.resolve("sine.wav");
        List<String> cmd = List.of("ffmpeg", "-hide_banner", "-loglevel", "error", "-y",
                "-f", "lavfi", "-i", "sine=frequency=440:duration=" + seconds,
                "-ar", "24000", "-ac", "1", out.toString());
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        p.getInputStream().readAllBytes();
        if (p.waitFor() != 0) {
            throw new IllegalStateException("造测试音频失败");
        }
        byte[] bytes = Files.readAllBytes(out);
        Files.deleteIfExists(out);
        Files.deleteIfExists(dir);
        return bytes;
    }

    private static boolean ffmpegAvailable() {
        try {
            Process p = new ProcessBuilder("ffmpeg", "-version").redirectErrorStream(true).start();
            p.getInputStream().readAllBytes();
            return p.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }
}
