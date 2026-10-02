package com.example.qqbot.onebot.outbound;

import com.example.qqbot.config.GuardProperties;
import com.example.qqbot.guard.OutboundPacer;
import com.example.qqbot.onebot.BotIdentity;
import com.example.qqbot.onebot.client.OneBotApiClient;
import com.example.qqbot.onebot.codec.MessageCodec;
import com.example.qqbot.onebot.model.OneBotEvent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 出站发送的验收测试 —— 重点是**合并转发这条新路**，以及它的失败兜底。
 *
 * <p>用的是真的 {@link MessageCodec} 和真的 {@link OutboundPacer}：
 * 要验的就是"发出去的 JSON 长什么样"和"怎么切的"，把这两个 mock 掉等于什么都没验。
 * 只有协议端（{@link OneBotApiClient}）是假的。
 */
class OutboundSenderTest {

    private static final long GROUP = 100L;
    private static final long USER = 200L;

    private final ObjectMapper mapper = new ObjectMapper();

    private OneBotApiClient api;
    private GuardProperties props;
    private GuardProperties.Outbound config;
    private BotIdentity identity;
    private OutboundSender sender;

    @BeforeEach
    void setUp() {
        api = mock(OneBotApiClient.class);
        props = new GuardProperties();
        config = props.getOutbound();
        config.setGroupMinIntervalMillis(0);   // 测试里不睡觉
        config.setGroupJitterMillis(0);
        config.setMaxCharsPerMessage(10);      // 10 字一段，方便切出多段
        config.setForwardMerged(true);
        config.setForwardIntroText("太长啦（{parts} 段）");

        identity = new BotIdentity();
        identity.setSelfId(999L);
        identity.setSelfName("飘雪喵");

        sender = new OutboundSender(api, new MessageCodec(mapper), new OutboundPacer(config), identity, config);
    }

    private OneBotEvent groupEvent() {
        OneBotEvent event = mock(OneBotEvent.class);
        when(event.isGroupMessage()).thenReturn(true);
        when(event.getGroupId()).thenReturn(GROUP);
        when(event.getUserId()).thenReturn(USER);
        return event;
    }

    private OneBotEvent privateEvent() {
        OneBotEvent event = mock(OneBotEvent.class);
        when(event.isPrivateMessage()).thenReturn(true);
        when(event.getUserId()).thenReturn(USER);
        return event;
    }

    /** 15 个字、上限 10 → 每句 5 字，正好切成 3 段 */
    private static final String THREE_PARTS = "一二三四五。六七八九十。甲乙丙丁戊。";
    private static final String TWO_PARTS = "一二三四五。六七八九十。";

    @Test
    @DisplayName("短回复：照旧一条普通消息，不碰合并转发")
    void shortReplyIsPlain() {
        sender.send(groupEvent(), "很短");

        verify(api).sendGroupMsg(eq(GROUP), any());
        verify(api, never()).sendGroupForwardMsg(anyLong(), any());
    }

    @Test
    @DisplayName("两段不合并：提示语 + 卡片也是 2 条，白折腾（阈值是 3）")
    void twoPartsStillPlain() {
        sender.send(groupEvent(), TWO_PARTS);

        verify(api, times(2)).sendGroupMsg(eq(GROUP), any());
        verify(api, never()).sendGroupForwardMsg(anyLong(), any());
    }

    @Test
    @DisplayName("★ 三段起：先发一条带 @ 的提示语，再用一条合并转发把三段打包出去")
    void mergesThreeParts() {
        sender.send(groupEvent(), THREE_PARTS);

        // 第 1 条：@ 提问者 + 提示语（{parts} 已替换）
        ArgumentCaptor<JsonNode> msg = ArgumentCaptor.forClass(JsonNode.class);
        verify(api, times(1)).sendGroupMsg(eq(GROUP), msg.capture());
        String at = msg.getValue().toString();
        assertThat(at).contains("\"at\"").contains(USER + "").contains("太长啦（3 段）");

        // 第 2 条：合并转发，节点数 = 段数，每个节点都署了机器人自己的名
        ArgumentCaptor<JsonNode> nodes = ArgumentCaptor.forClass(JsonNode.class);
        verify(api, times(1)).sendGroupForwardMsg(eq(GROUP), nodes.capture());
        JsonNode arr = nodes.getValue();
        assertThat(arr.isArray()).isTrue();
        assertThat(arr).hasSize(3);
        for (JsonNode node : arr) {
            assertThat(node.path("type").asText()).isEqualTo("node");
            JsonNode data = node.path("data");
            // 两套署名字段都要有：NapCat 认 user_id(数字)/nickname，go-cqhttp/Lagrange 认 uin/name
            assertThat(data.path("user_id").isNumber()).as("user_id 必须是数字（NapCat 的要求）").isTrue();
            assertThat(data.path("user_id").asLong()).isEqualTo(999L);
            assertThat(data.path("nickname").asText()).isEqualTo("飘雪喵");
            assertThat(data.path("uin").asText()).isEqualTo("999");
            assertThat(data.path("name").asText()).isEqualTo("飘雪喵");
            assertThat(data.path("content").isArray()).isTrue();
            assertThat(data.path("content").path(0).path("type").asText()).isEqualTo("text");
        }
        // 三段文本拼起来要和原文一致（没有丢字、没有重排）
        StringBuilder joined = new StringBuilder();
        for (JsonNode node : arr) {
            joined.append(node.path("data").path("content").path(0).path("data").path("text").asText());
        }
        assertThat(joined.toString().replace(" ", ""))
                .isEqualTo(THREE_PARTS.replace("。", "。").replace(" ", ""));
    }

    @Test
    @DisplayName("★ 协议端不支持合并转发 → 自动退回逐条，回复一个字都不能少")
    void fallsBackToPlainWhenForwardFails() {
        when(api.sendGroupForwardMsg(anyLong(), any()))
                .thenThrow(new RuntimeException("不支持的动作"));

        sender.send(groupEvent(), THREE_PARTS);

        // 提示语 1 条 + 退回的 3 条
        verify(api, times(4)).sendGroupMsg(eq(GROUP), any());
        // ⚠️ 提示语已经发出去了，退回时第一段**不能**再 @ 一次
        ArgumentCaptor<JsonNode> captor = ArgumentCaptor.forClass(JsonNode.class);
        verify(api, times(4)).sendGroupMsg(eq(GROUP), captor.capture());
        List<JsonNode> all = captor.getAllValues();
        assertThat(all.get(0).toString()).contains("\"at\"");
        for (int i = 1; i < all.size(); i++) {
            assertThat(all.get(i).toString()).as("第 %s 条不该再 @", i).doesNotContain("\"at\"");
        }
    }

    @Test
    @DisplayName("开关关掉：完全走老路（逐条 + 第一段 @）")
    void switchOffKeepsOldBehaviour() {
        config.setForwardMerged(false);

        sender.send(groupEvent(), THREE_PARTS);

        verify(api, never()).sendGroupForwardMsg(anyLong(), any());
        ArgumentCaptor<JsonNode> captor = ArgumentCaptor.forClass(JsonNode.class);
        verify(api, times(3)).sendGroupMsg(eq(GROUP), captor.capture());
        assertThat(captor.getAllValues().get(0).toString()).contains("\"at\"");
    }

    @Test
    @DisplayName("还不知道自己的 QQ 号（启动时没连上协议层）：不合并，但照常回复")
    void withoutSelfIdFallsBack() {
        identity.setSelfId(0);

        sender.send(groupEvent(), THREE_PARTS);

        verify(api, never()).sendGroupForwardMsg(anyLong(), any());
        verify(api, times(3)).sendGroupMsg(eq(GROUP), any());
    }

    @Test
    @DisplayName("私聊也合并（私聊没有 @ 这一步，只有卡片）")
    void mergesPrivateToo() {
        sender.send(privateEvent(), THREE_PARTS);

        verify(api, never()).sendPrivateMsg(anyLong(), any());
        verify(api).sendPrivateForwardMsg(eq(USER), any());
    }

    @Test
    @DisplayName("段数阈值可调：设成 4 时，3 段就不合并了")
    void configurableMinParts() {
        config.setForwardMinParts(4);

        sender.send(groupEvent(), THREE_PARTS);

        verify(api, never()).sendGroupForwardMsg(anyLong(), any());
        verify(api, times(3)).sendGroupMsg(eq(GROUP), any());
    }

    @Test
    @DisplayName("字数阈值：总字数不够就不合并（哪怕已经切了 3 段）")
    void charThresholdBlocksShortText() {
        config.setForwardMinChars(100);   // 这条只有 18 个字

        sender.send(groupEvent(), THREE_PARTS);

        verify(api, never()).sendGroupForwardMsg(anyLong(), any());
        verify(api, times(3)).sendGroupMsg(eq(GROUP), any());
    }

    @Test
    @DisplayName("两个阈值是「与」：段数和字数都满足才合并")
    void bothThresholdsMustPass() {
        config.setForwardMinParts(2);     // 3 段 > 2 ✓
        config.setForwardMinChars(10);    // 18 字 > 10 ✓

        sender.send(groupEvent(), THREE_PARTS);

        verify(api).sendGroupForwardMsg(eq(GROUP), any());
        verify(api, times(1)).sendGroupMsg(eq(GROUP), any());   // 只有那条提示语
    }

    @Test
    @DisplayName("两个阈值都设 0 = 不限制，什么长度都打包（显式选择，别当默认）")
    void zeroMeansNoLimit() {
        config.setForwardMinParts(0);
        config.setForwardMinChars(0);

        sender.send(groupEvent(), TWO_PARTS);

        verify(api).sendGroupForwardMsg(eq(GROUP), any());
    }

    @Test
    @DisplayName("切分规则没变：中文句号处切、英文句点不切（网址不会被切两半）")
    void splitRulesUnchanged() {
        OutboundPacer pacer = new OutboundPacer(config);
        config.setMaxCharsPerMessage(300);

        assertThat(pacer.split("第一句。第二句。")).hasSize(1);   // 没超限 → 不切
        String url = "https://enshrouded.wiki.gg/wiki/Scrap_Cup";
        assertThat(pacer.split(url)).containsExactly(url);          // 英文句点不切
    }
}
