package com.example.qqbot.settings;

import com.example.qqbot.config.BotProperties;
import com.example.qqbot.config.CommandProperties;
import com.example.qqbot.config.GuardProperties;
import com.example.qqbot.config.IntentProperties;
import com.example.qqbot.config.KbProperties;
import com.example.qqbot.config.LlmProperties;
import com.example.qqbot.config.LogProperties;
import com.example.qqbot.kb.MapOptions;
import com.example.qqbot.config.MediaProperties;
import com.example.qqbot.config.QaProperties;
import com.example.qqbot.config.SiteProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.example.qqbot.config.SettingsConfig;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
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
                mock(GuardProperties.class), mock(KbProperties.class), mock(MapOptions.class),
                mock(MediaProperties.class), mock(QaProperties.class),
                mock(LogProperties.class), mock(CommandProperties.class),
                mock(SiteProperties.class), mock(LlmProperties.class),
                mock(BotProperties.class), mock(IntentProperties.class));
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

    @Test
    @DisplayName("★ 白名单里每一项都要能解析到「对象 + 字段」—— 前缀有人接管、路径对不上照样改不动")
    void everyWhitelistedKeyResolves() {
        // ⚠️ 这里用**真实**的配置对象，不能用 mock：
        //    带嵌套对象的 key（app.media.temp-images.dir、app.kb.map.shot.enabled）要靠 getter 往里走，
        //    mock 的 getter 返回 null，测出来的"坏"是假的
        SettingsService service = new SettingsService(new SettingsWhitelist(),
                mock(OverridesFile.class),
                new SettingsConfig().settingsRoots(
                        new GuardProperties(), new KbProperties(), new MapOptions(),
                        new MediaProperties(), new QaProperties(), new LogProperties(),
                        new CommandProperties(), new SiteProperties(), new LlmProperties(),
                        new BotProperties(), new IntentProperties()));

        List<String> broken = new ArrayList<>();
        for (SettingsWhitelist.Item item : SettingsWhitelist.ITEMS) {
            if (!service.canResolve(item.key())) {
                broken.add(item.key());
            }
        }

        // 2026-10-15：app.kb.map.* 三项就是这么坏的 —— 白名单加了、多段前缀没注册，
        // 界面上看得见、一改就报「配置路径不存在」
        assertThat(broken)
                .as("这些配置项在界面上改不动（解析不到承载它们的对象与字段）")
                .isEmpty();
    }
}
