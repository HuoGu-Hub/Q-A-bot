package com.example.qqbot.web;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import io.swagger.v3.oas.models.tags.Tag;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * OpenAPI 文档的固定部分。
 *
 * <p>路径与 schema 由 springdoc 从控制器和 record 推导，这里只补
 * 标题、鉴权方式与分组标签。规范默认不对外暴露，生成时用
 * {@code SPRINGDOC_ENABLED=true} 打开。
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI qqbotOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Q-A-bot API")
                        .version("1.0.0")
                        .description("""
                                公开站只读接口与管理后台接口。

                                公开接口按 IP 限流，触发时返回 429 与 Retry-After。
                                管理接口使用 admin_token 会话 Cookie；未配置后台账号时整体返回 503。
                                OneBot 事件入口使用 x-signature 或 Bearer Token。
                                """))
                .servers(List.of(new Server().url("/")))
                .tags(List.of(
                        new Tag().name("Public").description("公开站只读接口"),
                        new Tag().name("Admin").description("管理后台接口，需要 admin_token"),
                        new Tag().name("OneBot").description("NapCat 事件上报")))
                .components(new Components()
                        .addSecuritySchemes("adminSession", new SecurityScheme()
                                .type(SecurityScheme.Type.APIKEY)
                                .in(SecurityScheme.In.COOKIE)
                                .name("admin_token"))
                        .addSecuritySchemes("oneBotSignature", new SecurityScheme()
                                .type(SecurityScheme.Type.APIKEY)
                                .in(SecurityScheme.In.HEADER)
                                .name("x-signature")));
    }
}
