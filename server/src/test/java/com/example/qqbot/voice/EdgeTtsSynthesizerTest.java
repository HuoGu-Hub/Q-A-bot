package com.example.qqbot.voice;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * edge-tts 实现的**可用性判断**。
 *
 * <p>真正的合成要联网，放在手动验证里跑；这里锁住的是"什么时候该说自己不可用" ——
 * 判断错了会让调用方要么白等两秒，要么在有网时错误地退回文字。
 */
class EdgeTtsSynthesizerTest {

    @TempDir
    Path tmp;

    @Test
    @DisplayName("开关关掉就不可用")
    void disabledIsUnavailable() {
        EdgeTtsSynthesizer s = synth(false, "edge-tts", "edge-tts");
        assertThat(s.isAvailable()).isFalse();
    }

    @Test
    @DisplayName("provider 不是 edge-tts 时不可用（留给以后换 Azure）")
    void otherProviderIsUnavailable() {
        EdgeTtsSynthesizer s = synth(true, "azure", "edge-tts");
        assertThat(s.isAvailable()).isFalse();
    }

    @Test
    @DisplayName("写成路径但文件不存在：不可用")
    void missingPathIsUnavailable() {
        EdgeTtsSynthesizer s = synth(true, "edge-tts", tmp.resolve("nope.exe").toString());
        assertThat(s.isAvailable()).isFalse();
    }

    @Test
    @DisplayName("指到真实文件：可用")
    void existingPathIsAvailable() throws Exception {
        Path exe = tmp.resolve("edge-tts.exe");
        java.nio.file.Files.writeString(exe, "stub");

        EdgeTtsSynthesizer s = synth(true, "edge-tts", exe.toString());

        assertThat(s.isAvailable()).isTrue();
    }

    @Test
    @DisplayName("关掉后直接合成会明确报错，而不是静默返回空")
    void synthesizeWhenDisabledThrows() {
        EdgeTtsSynthesizer s = synth(false, "edge-tts", "edge-tts");
        assertThatThrownBy(() -> s.synthesize("你好"))
                .isInstanceOf(VoiceException.class)
                .hasMessageContaining("已关闭");
    }

    private EdgeTtsSynthesizer synth(boolean enabled, String provider, String path) {
        TtsPolicy policy = new TtsPolicy() {
            @Override public boolean isTtsEnabled() { return enabled; }
            @Override public String getTtsProvider() { return provider; }
            @Override public String getEdgeTtsPath() { return path; }
            @Override public String getTtsVoice() { return "zh-CN-XiaoxiaoNeural"; }
            @Override public String getTtsRate() { return "+0%"; }
            @Override public String getTtsPitch() { return "+0Hz"; }
            @Override public String getTtsVolume() { return "+0%"; }
            @Override public int getTtsMaxChars() { return 200; }
            @Override public int getTtsTimeoutSeconds() { return 5; }
            @Override public String getTtsCacheDir() { return tmp.resolve("cache").toString(); }
        };
        return new EdgeTtsSynthesizer(policy, new VoiceCache(policy));
    }
}
