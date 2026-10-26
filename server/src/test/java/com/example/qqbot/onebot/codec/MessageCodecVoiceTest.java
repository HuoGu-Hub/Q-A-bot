package com.example.qqbot.onebot.codec;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 语音段构造测试 —— 断言的是**最终发给协议端的 JSON**。
 *
 * <p>这条链路已经在真机上验证过：{@code record} 段 + base64 音频，
 * NapCat 会转成 SILK 再发出去。这里锁住三个最容易退化的点：
 * 段类型、base64 前缀、以及"空音频不发"。
 */
class MessageCodecVoiceTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final MessageCodec codec = new MessageCodec(mapper);

    @Test
    @DisplayName("构造 record 段：一个段、类型是 record、file 是 base64:// 前缀")
    void buildsRecordSegment() {
        byte[] audio = new byte[]{1, 2, 3, 4, 5};

        JsonNode message = codec.voiceMessage(audio);

        assertThat(message.isArray()).isTrue();
        assertThat(message).hasSize(1);
        JsonNode segment = message.get(0);
        assertThat(segment.path("type").asText()).isEqualTo("record");

        String file = segment.path("data").path("file").asText();
        assertThat(file).startsWith("base64://");
        assertThat(Base64.getDecoder().decode(file.substring("base64://".length())))
                .containsExactly(audio);
    }

    @Test
    @DisplayName("空音频当场拒绝：发出去只会是一条怪东西")
    void rejectsEmptyAudio() {
        assertThatThrownBy(() -> codec.voiceMessage(new byte[0]))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("语音内容为空");
        assertThatThrownBy(() -> codec.voiceMessage(null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
