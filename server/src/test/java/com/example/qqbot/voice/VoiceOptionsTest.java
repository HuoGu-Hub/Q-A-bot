package com.example.qqbot.voice;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 加工参数与滤镜链的验收 —— ffmpeg 参数写错的代价是"发出去没声音"，很难在群里发现。
 *
 * <p>这里只验**拼出来的字符串**，不跑 ffmpeg：真正跑一遍的成本放在
 * {@link VoiceProcessorTest} 里，那边没有 ffmpeg 会跳过。
 */
class VoiceOptionsTest {

    @Test
    @DisplayName("默认参数不产生任何滤镜")
    void noneProducesNoFilters() {
        assertThat(VoiceOptions.NONE.isPassthrough()).isTrue();
        assertThat(VoiceProcessor.filterChain(VoiceOptions.NONE)).isEmpty();
    }

    @Test
    @DisplayName("裁剪：先 atrim 再把时间戳归零，否则后续滤镜会带着原时间轴")
    void trimFilter() {
        VoiceOptions opts = VoiceOptions.builder().start(1.5).duration(30).build();

        String chain = VoiceProcessor.filterChain(opts);

        assertThat(chain).isEqualTo("atrim=start=1.500:duration=30.000,asetpts=PTS-STARTPTS");
        assertThat(opts.isPassthrough()).isFalse();
    }

    @Test
    @DisplayName("变调：改采样率升调，再用 atempo 的倒数把时长拉回来")
    void pitchFilter() {
        String chain = VoiceProcessor.filterChain(VoiceOptions.builder().semitones(12).build());

        // 12 个半音 = 2 倍频，asetrate 24000 → 48000；atempo 取 0.5 抵消
        assertThat(chain).contains("asetrate=48000").contains("atempo=0.500");
    }

    @Test
    @DisplayName("变速与音量各自独立，顺序是 变调 → 变速 → 音量")
    void speedAndGainOrder() {
        String chain = VoiceProcessor.filterChain(
                VoiceOptions.builder().semitones(-12).speed(1.5).gainDb(-3).build());

        assertThat(chain.indexOf("asetrate")).isLessThan(chain.indexOf("atempo=1.500"));
        assertThat(chain.indexOf("atempo=1.500")).isLessThan(chain.indexOf("volume=-3.000dB"));
    }

    @Test
    @DisplayName("归一化走 loudnorm，参数固定，群里不会忽大忽小")
    void normalizeFilter() {
        assertThat(VoiceProcessor.filterChain(VoiceOptions.builder().normalize(true).build()))
                .isEqualTo("loudnorm=I=-16:TP=-1.5:LRA=11");
    }

    @Test
    @DisplayName("越界参数当场拒绝，不做静默截断")
    void rejectsOutOfRange() {
        assertThatThrownBy(() -> VoiceOptions.builder().speed(3).build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("speed");
        assertThatThrownBy(() -> VoiceOptions.builder().semitones(24).build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("semitones");
        assertThatThrownBy(() -> VoiceOptions.builder().start(-1).build())
                .isInstanceOf(IllegalArgumentException.class);
    }
}
