package com.example.qqbot.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.FileSystemResource;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/** 配置文件里的内联列表能按 Long 绑进白名单 */
class ReplyTriggerBindingTest {

    @Test
    @DisplayName("项目里的 overrides.yml 也能绑定")
    void bindsProjectOverrides() throws Exception {
        YamlPropertySourceLoader loader = new YamlPropertySourceLoader();
        MutablePropertySources sources = new MutablePropertySources();
        loader.load("overrides", new FileSystemResource("config/overrides.yml"))
                .forEach(sources::addLast);

        ReplyTriggerProperties props = new Binder(ConfigurationPropertySources.from(sources))
                .bind("app.reply", Bindable.of(ReplyTriggerProperties.class))
                .orElseGet(ReplyTriggerProperties::new);

        assertThat(props.enabledGroups()).containsExactly(209127588L);
    }

    @Test
    @DisplayName("enabled-groups: [209127588] 绑定成 List<Long>")
    void bindsInlineGroupList() throws Exception {
        String yaml = """
                app:
                  reply:
                    probability: 0.25
                    enabled-groups: [209127588]
                    daily-max-per-group: 40
                """;
        YamlPropertySourceLoader loader = new YamlPropertySourceLoader();
        MutablePropertySources sources = new MutablePropertySources();
        loader.load("test", new ByteArrayResource(yaml.getBytes(StandardCharsets.UTF_8)))
                .forEach(sources::addLast);

        ReplyTriggerProperties props = new Binder(ConfigurationPropertySources.from(sources))
                .bind("app.reply", Bindable.of(ReplyTriggerProperties.class))
                .orElseGet(ReplyTriggerProperties::new);

        assertThat(props.probability()).isEqualTo(0.25);
        assertThat(props.enabledGroups()).containsExactly(209127588L);
        assertThat(props.dailyMaxPerGroup()).isEqualTo(40);
    }
}
