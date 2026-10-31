package com.example.qqbot.web;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.headers.Header;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.ArraySchema;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.IntegerSchema;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import io.swagger.v3.oas.models.tags.Tag;
import org.springdoc.core.customizers.OperationCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.ResolvableType;
import org.springframework.http.ResponseEntity;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * OpenAPI 文档的固定部分。
 *
 * <p>路径与 schema 由 springdoc 从控制器和 record 推导，这里只补
 * 标题、鉴权方式、错误响应与分组标签。规范默认不对外暴露，生成时用
 * {@code SPRINGDOC_ENABLED=true} 打开。
 */
@Configuration
public class OpenApiConfig {

    /**
     * 统一修正状态码与错误响应。
     *
     * <p>{@code ResponseEntity<Void>} 的实际状态是 204，但 springdoc 默认记成 200。
     * 同一个 customizer 里补 problem+json，避免多个 customizer 的注册顺序不确定。
     *
     * <p>公开接口没有登录态，所以不硬塞 401/403；管理接口与 OneBot 才有。
     */
    @Bean
    public OperationCustomizer responseCustomizer() {
        return (Operation operation, org.springframework.web.method.HandlerMethod handlerMethod) -> {
            ApiResponses responses = operation.getResponses();
            if (responses == null) {
                return operation;
            }

            ResolvableType type = ResolvableType.forMethodReturnType(handlerMethod.getMethod());
            if (ResponseEntity.class.isAssignableFrom(type.resolve(Object.class))
                    && type.getGeneric(0).resolve() == Void.class
                    && responses.remove("200") != null) {
                responses.addApiResponse("204", new ApiResponse().description("无内容"));
            }

            List<String> tags = operation.getTags();
            boolean admin = tags != null && tags.contains("Admin");
            boolean oneBot = tags != null && tags.contains("OneBot");

            addIfAbsent(responses, "400", problem("请求参数或请求体不合法"));
            addIfAbsent(responses, "404", problem("资源或接口不存在"));
            addIfAbsent(responses, "429", rateLimited());
            addIfAbsent(responses, "500", problem("服务端处理失败"));
            if (admin || oneBot) {
                addIfAbsent(responses, "401", problem("未登录、会话过期或鉴权失败"));
                addIfAbsent(responses, "403", problem("当前账号没有权限"));
                addIfAbsent(responses, "503", problem("后台未启用或 OneBot Token 未配置"));
            }
            return operation;
        };
    }

    private static void addIfAbsent(ApiResponses responses, String status, ApiResponse response) {
        if (!responses.containsKey(status)) {
            responses.addApiResponse(status, response);
        }
    }

    private static ApiResponse problem(String description) {
        return new ApiResponse()
                .description(description)
                .content(new Content().addMediaType("application/problem+json",
                        new MediaType().schema(new Schema<>().$ref("#/components/schemas/ProblemDetail"))));
    }

    private static ApiResponse rateLimited() {
        ApiResponse response = problem("请求过于频繁，按 Retry-After 重试");
        Map<String, Header> headers = new LinkedHashMap<>();
        headers.put("Retry-After", new Header()
                .description("多少秒后可以重试")
                .schema(new IntegerSchema()));
        response.setHeaders(headers);
        return response;
    }

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
                        .addSchemas("ProblemDetail", problemDetailSchema())
                        .addSecuritySchemes("adminSession", new SecurityScheme()
                                .type(SecurityScheme.Type.APIKEY)
                                .in(SecurityScheme.In.COOKIE)
                                .name("admin_token"))
                        .addSecuritySchemes("oneBotSignature", new SecurityScheme()
                                .type(SecurityScheme.Type.APIKEY)
                                .in(SecurityScheme.In.HEADER)
                                .name("x-signature")));
    }

    private static Schema<?> problemDetailSchema() {
        ObjectSchema schema = new ObjectSchema();
        schema.setDescription("统一错误响应，Content-Type 为 application/problem+json");
        schema.addProperty("type", new StringSchema().format("uri")
                .description("错误类型 URI"));
        schema.addProperty("title", new StringSchema().description("HTTP 状态标题"));
        schema.addProperty("status", new IntegerSchema().description("HTTP 状态码"));
        schema.addProperty("detail", new StringSchema().description("给人看的错误说明"));
        schema.addProperty("instance", new StringSchema().format("uri")
                .description("请求路径"));
        schema.addProperty("code", new StringSchema().description("给程序判断的稳定错误码"));
        schema.addProperty("requestId", new StringSchema().description("请求标识，和响应头 X-Request-Id 一致"));

        ObjectSchema fieldProblem = new ObjectSchema();
        fieldProblem.addProperty("field", new StringSchema());
        fieldProblem.addProperty("message", new StringSchema());
        ArraySchema errors = new ArraySchema();
        errors.setDescription("字段级校验错误，只有校验失败时出现");
        errors.setItems(fieldProblem);
        schema.addProperty("errors", errors);

        schema.setRequired(List.of("type", "title", "status", "code", "detail", "requestId"));
        return schema;
    }
}
