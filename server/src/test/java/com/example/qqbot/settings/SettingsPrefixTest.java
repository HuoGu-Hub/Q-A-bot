package com.example.qqbot.settings;

import com.example.qqbot.config.BotProperties;
import com.example.qqbot.config.CommandProperties;
import com.example.qqbot.config.GuardProperties;
import com.example.qqbot.config.KbProperties;
import com.example.qqbot.config.LlmProperties;
import com.example.qqbot.config.LogProperties;
import com.example.qqbot.config.MediaProperties;
import com.example.qqbot.config.QaProperties;
import com.example.qqbot.config.SiteProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.example.qqbot.config.SettingsConfig;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * 白名单里的每个顶层前缀，都必须有人在 {@link SettingsService} 里接管。
 *
 * <p><b>真实故障（2026-10-01）</b>：白名单加了 5 项「回复风格」
 * （{@code app.llm.reply-style.*}），但 {@code SettingsService} 的前缀分发里没有 llm ——
 * 用户在配置中心怎么点都失败，报「不支持的配置前缀：llm」，而且**每个字段各报一次**，
 * 从错误信息看不出是"漏注册了一个前缀"。
 *
 * <p>为什么以前没被发现：白名单和分发逻辑在两个地方，加白名单的人不会顺手去看
 * {@code resolve()} 的 switch。这条测试把两边钉在一起 —— 以后谁再往白名单里加新前缀，
 * 忘了注册就会在这里红。
 */
class SettingsPrefixTest {

    @Test
    @DisplayName("★ 白名单里出现的每个顶层前缀，都必须有配置对象接管（别再漏 llm 这种）")
    void everyWhitelistedPrefixIsRouted() {
        // ⚠️ 表来自**真实的装配代码**（SettingsConfig），不是测试自己拼的 ——
        //    否则「加了白名单却忘了注册」这件事就测不出来了。
        SettingsRoots roots = new SettingsConfig().settingsRoots(
                mock(GuardProperties.class), mock(KbProperties.class),
                mock(MediaProperties.class), mock(QaProperties.class),
                mock(LogProperties.class), mock(CommandProperties.class),
                mock(SiteProperties.class), mock(LlmProperties.class),
                mock(BotProperties.class));
        SettingsService service = new SettingsService(
                new SettingsWhitelist(), mock(OverridesFile.class), roots);

        Set<String> prefixes = new LinkedHashSet<>();
        Set<String> missing = new LinkedHashSet<>();
        for (SettingsWhitelist.Item item : SettingsWhitelist.ITEMS) {
            String[] parts = item.key().split("\\.");
            assertThat(parts)
                    .as("配置键至少要形如 app.xxx.yyy：%s", item.key())
                    .hasSizeGreaterThanOrEqualTo(3);
            String prefix = parts[1];
            prefixes.add(prefix);
            if (!service.supportsPrefix(prefix)) {
                missing.add(prefix + "（来自 " + item.key() + "）");
            }
        }

        assertThat(prefixes).as("白名单不能是空的").isNotEmpty();
        assertThat(missing)
                .as("这些前缀没有配置对象接管，改它们必然报「不支持的配置前缀」")
                .isEmpty();
    }
}
