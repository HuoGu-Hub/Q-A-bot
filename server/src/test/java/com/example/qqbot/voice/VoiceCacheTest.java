package com.example.qqbot.voice;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 合成缓存的验收：命中要快、未命中不能假装命中、参数不同就是另一段音频。
 */
class VoiceCacheTest {

    @TempDir
    Path tmp;

    private VoiceCache cache;

    @BeforeEach
    void setUp() {
        TtsPolicy policy = policy(tmp.resolve("tts").toString());
        cache = new VoiceCache(policy);
    }

    @Test
    @DisplayName("写进去能读出来，内容一致")
    void storesAndLoads() {
        cache.put("abc", new byte[]{1, 2, 3});

        assertThat(cache.get("abc")).containsExactly(1, 2, 3);
    }

    @Test
    @DisplayName("没写过返回 null，而不是空数组")
    void missReturnsNull() {
        assertThat(cache.get("nope")).isNull();
    }

    @Test
    @DisplayName("同一个 key 覆盖写，不会留下两份")
    void overwritesSameKey() {
        cache.put("k", new byte[]{1});
        cache.put("k", new byte[]{2, 3});

        assertThat(cache.get("k")).containsExactly(2, 3);
    }

    private static TtsPolicy policy(String dir) {
        return new TtsPolicy() {
            @Override public boolean isTtsEnabled() { return true; }
            @Override public String getTtsProvider() { return "edge-tts"; }
            @Override public String getEdgeTtsPath() { return "edge-tts"; }
            @Override public String getTtsVoice() { return "zh-CN-XiaoxiaoNeural"; }
            @Override public String getTtsRate() { return "+0%"; }
            @Override public String getTtsPitch() { return "+0Hz"; }
            @Override public String getTtsVolume() { return "+0%"; }
            @Override public int getTtsMaxChars() { return 200; }
            @Override public int getTtsTimeoutSeconds() { return 30; }
            @Override public String getTtsCacheDir() { return dir; }
        };
    }
}
