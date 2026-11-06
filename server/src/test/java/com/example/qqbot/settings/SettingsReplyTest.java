package com.example.qqbot.settings;

import com.example.qqbot.config.BotProperties;
import com.example.qqbot.config.CommandProperties;
import com.example.qqbot.config.GuardProperties;
import com.example.qqbot.config.IntentProperties;
import com.example.qqbot.config.KbProperties;
import com.example.qqbot.config.LlmProperties;
import com.example.qqbot.config.LogProperties;
import com.example.qqbot.config.MediaProperties;
import com.example.qqbot.config.QaProperties;
import com.example.qqbot.config.ReplyTriggerProperties;
import com.example.qqbot.config.SettingsConfig;
import com.example.qqbot.config.SiteProperties;
import com.example.qqbot.kb.MapOptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/** 概率回复的配置写入：概率范围、群白名单按 Long 命中 */
class SettingsReplyTest {

    @Test
    @DisplayName("概率越界被拒绝，白名单写进去能按 Long 命中")
    void probabilityAndGroups() {
        ReplyTriggerProperties reply = new ReplyTriggerProperties();
        SettingsRoots roots = new SettingsConfig().settingsRoots(
                mock(GuardProperties.class), mock(KbProperties.class), mock(MapOptions.class),
                mock(MediaProperties.class), mock(QaProperties.class), mock(LogProperties.class),
                mock(CommandProperties.class), mock(SiteProperties.class), mock(LlmProperties.class),
                mock(BotProperties.class), mock(IntentProperties.class), reply);
        SettingsService service = new SettingsService(new SettingsWhitelist(),
                mock(OverridesFile.class), roots);

        assertThat(service.apply(Map.of("app.reply.probability", 1.5)).errors())
                .containsKey("app.reply.probability");
        assertThat(reply.probability()).isZero();

        assertThat(service.apply(Map.of("app.reply.probability", 0.35)).errors()).isEmpty();
        assertThat(reply.probability()).isEqualTo(0.35);

        assertThat(service.apply(Map.of("app.reply.enabled-groups", List.of("209127588"))).errors()).isEmpty();
        assertThat(reply.enabledGroups()).containsExactly(209127588L);

        assertThat(service.apply(Map.of("app.reply.enabled-groups",
                List.of("209127588", "209127589"))).errors()).isEmpty();
        assertThat(reply.enabledGroups()).containsExactly(209127588L, 209127589L);
    }
}
