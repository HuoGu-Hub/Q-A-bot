package com.example.qqbot.kb.map;

import com.example.qqbot.kb.MapOptions;
import com.example.qqbot.media.UpstreamGate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/** 两条取图路径的 URL 形状：Special:FilePath 与 403 兜底用的直链。 */
class WikiMapImageSourceUrlTest {

    private WikiMapImageSource source() {
        return new WikiMapImageSource(new MapOptions(), mock(UpstreamGate.class));
    }

    @Test
    @DisplayName("正常路径仍走 Special:FilePath，保留 ?width=")
    void primaryUrl() {
        WikiMapImageSource s = source();
        assertThat(s.url("2_7.jpg", 640))
                .isEqualTo("https://enshrouded.wiki.gg/wiki/Special:FilePath/2_7.jpg?width=640");
        assertThat(s.url("Mine.png", 0))
                .isEqualTo("https://enshrouded.wiki.gg/wiki/Special:FilePath/Mine.png");
    }

    @Test
    @DisplayName("403 兜底直链：缩略图走 /images/thumb，原图走 /images/")
    void directUrl() {
        WikiMapImageSource s = source();
        assertThat(s.directUrl("2_7.jpg", 640))
                .isEqualTo("https://enshrouded.wiki.gg/images/thumb/2_7.jpg/640px-2_7.jpg");
        assertThat(s.directUrl("2_7.jpg", 320))
                .isEqualTo("https://enshrouded.wiki.gg/images/thumb/2_7.jpg/320px-2_7.jpg");
        assertThat(s.directUrl("Mine.png", 0))
                .isEqualTo("https://enshrouded.wiki.gg/images/Mine.png");
    }
}
