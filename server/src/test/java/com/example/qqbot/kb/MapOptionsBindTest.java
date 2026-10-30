package com.example.qqbot.kb;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

/** 配置键真的能绑到 {@link MapOptions.Resource} 上，而不只是写在 yml 里好看。 */
class MapOptionsBindTest {

    @Configuration
    @EnableConfigurationProperties(MapOptions.class)
    static class Config {
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(Config.class);

    @Test
    @DisplayName("app.kb.map.resource.* 按 kebab-case 绑定到字段")
    void bindsResourceKeys() {
        runner.withPropertyValues(
                        "app.kb.map.resource.cell-size=200",
                        "app.kb.map.resource.nms-radius=800",
                        "app.kb.map.resource.hot-radius=300",
                        "app.kb.map.resource.top=2",
                        "app.kb.map.resource.label-max=5",
                        "app.kb.map.resource.max-context=20")
                .run(ctx -> {
                    MapOptions.Resource r = ctx.getBean(MapOptions.class).getResource();
                    assertThat(r.getCellSize()).isEqualTo(200);
                    assertThat(r.getNmsRadius()).isEqualTo(800);
                    assertThat(r.getHotRadius()).isEqualTo(300);
                    assertThat(r.getTop()).isEqualTo(2);
                    assertThat(r.getLabelMax()).isEqualTo(5);
                    assertThat(r.getMaxContext()).isEqualTo(20);
                });
    }
}
