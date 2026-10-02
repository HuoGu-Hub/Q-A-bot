package com.example.qqbot.agent;

import com.example.qqbot.guard.ContentGate;
import com.example.qqbot.config.KbProperties;
import com.example.qqbot.config.MediaProperties;
import com.example.qqbot.kb.KbRetriever;
import com.example.qqbot.llm.LlmRouter;
import com.example.qqbot.media.ImageFetcher;
import com.example.qqbot.onebot.model.ImageRef;
import com.example.qqbot.onebot.model.OneBotEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 单条消息图片数上限的验收测试。
 *
 * <p>这是内存安全的闸门：图片是同时读进内存再转 Base64 的，单张峰值约文件大小的 2.33 倍，
 * 再乘上事件线程池的并发数。所以要断言的不是"回复对不对"，而是**到底下载/编码了几张**。
 */
class ChatServiceImageLimitTest {

    private LlmRouter llmRouter;
    private ImageFetcher imageFetcher;
    private MediaProperties mediaProperties;
    private ChatService chatService;

    @BeforeEach
    void setUp() {
        llmRouter = mock(LlmRouter.class);
        imageFetcher = mock(ImageFetcher.class);
        mediaProperties = new MediaProperties();

        when(llmRouter.isAvailable()).thenReturn(true);
        when(llmRouter.hasVision()).thenReturn(true);
        when(llmRouter.chatWithImages(anyString(), anyList())).thenReturn("看到了");
        when(imageFetcher.fetch(any(ImageRef.class)))
                .thenReturn(Optional.of(new ImageFetcher.FetchedImage("QUJD", "image/jpeg", 3)));

        chatService = new ChatService(llmRouter, imageFetcher, new ContentGate(), mediaProperties,
                mock(KbRetriever.class), new KbProperties());
    }

    @Test
    @DisplayName("★ 一条消息带 5 张图时只下载 3 张（默认上限）")
    void limitsImagesPerMessage() {
        OneBotEvent event = mock(OneBotEvent.class);

        chatService.reply(event, "看看这几张", null, refs(5));

        verify(imageFetcher, times(3)).fetch(any(ImageRef.class));
    }

    @Test
    @DisplayName("不超过上限时原样处理，不误伤")
    void passesThroughWhenUnderLimit() {
        OneBotEvent event = mock(OneBotEvent.class);

        chatService.reply(event, "看看这两张", null, refs(2));

        verify(imageFetcher, times(2)).fetch(any(ImageRef.class));
    }

    @Test
    @DisplayName("上限可配置")
    void limitIsConfigurable() {
        mediaProperties.setMaxImagesPerMessage(1);
        OneBotEvent event = mock(OneBotEvent.class);

        chatService.reply(event, "看看", null, refs(4));

        verify(imageFetcher, times(1)).fetch(any(ImageRef.class));
    }

    @Test
    @DisplayName("配成 0 表示不限制")
    void zeroMeansUnlimited() {
        mediaProperties.setMaxImagesPerMessage(0);
        OneBotEvent event = mock(OneBotEvent.class);

        chatService.reply(event, "看看", null, refs(6));

        verify(imageFetcher, times(6)).fetch(any(ImageRef.class));
    }

    @Test
    @DisplayName("纯文字消息不会去碰图片")
    void textOnlyDoesNotTouchImages() {
        OneBotEvent event = mock(OneBotEvent.class);
        when(llmRouter.chat(anyString())).thenReturn("好的");

        chatService.reply(event, "你好", null, List.of());

        verify(imageFetcher, never()).fetch(any(ImageRef.class));
    }

    private static List<ImageRef> refs(int count) {
        List<ImageRef> list = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            list.add(new ImageRef(String.format("%032x", i), "https://example.com/" + i + ".jpg", 100));
        }
        return list;
    }
}
