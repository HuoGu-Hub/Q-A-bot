package com.example.qqbot.voice;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 待发清单的上限与取走语义 —— 它决定了"一条回复最多几段语音"。 */
class MediaOutboxTest {

    @Test
    @DisplayName("最多记两条，第三条被拒绝")
    void capsAtTwo() {
        MediaOutbox box = new MediaOutbox();

        assertThat(box.add(MediaOutbox.Intent.asset(1, "a"))).isTrue();
        assertThat(box.add(MediaOutbox.Intent.speech("b"))).isTrue();
        assertThat(box.add(MediaOutbox.Intent.asset(2, "c"))).isFalse();
        assertThat(box.size()).isEqualTo(2);
    }

    @Test
    @DisplayName("取走之后清空，重复取走返回空")
    void drainClears() {
        MediaOutbox box = new MediaOutbox();
        box.add(MediaOutbox.Intent.speech("晚安"));

        List<MediaOutbox.Intent> first = box.drain();
        List<MediaOutbox.Intent> second = box.drain();

        assertThat(first).hasSize(1);
        assertThat(second).isEmpty();
        assertThat(box.isEmpty()).isTrue();
    }

    @Test
    @DisplayName("空文本也能记，但合成阶段会拒绝 —— 这里只保证不 NPE")
    void toleratesNullText() {
        MediaOutbox.Intent intent = MediaOutbox.Intent.speech(null);
        assertThat(intent.text()).isEmpty();
    }
}
