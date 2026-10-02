package com.example.qqbot.onebot.client;

import com.example.qqbot.config.OneBotProperties;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;

/**
 * OneBot 11 的「动作」调用客户端 —— 这是整个业务层唯一能往 QQ 发消息的地方。
 *
 * <p>所有出站消息都从这里走。以后要加限流、审计、重试，只需要改这一个类。
 */
@Component
public class OneBotApiClient {

    private static final Logger log = LoggerFactory.getLogger(OneBotApiClient.class);

    private final RestClient restClient;

    public OneBotApiClient(OneBotProperties props) {
        Duration timeout = Duration.ofMillis(props.getRequestTimeoutMs());
        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(timeout).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(timeout);

        RestClient.Builder builder = RestClient.builder()
                .baseUrl(props.getApiBase())
                .requestFactory(factory);

        if (StringUtils.hasText(props.getAccessToken())) {
            builder = builder.defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + props.getAccessToken());
        }
        this.restClient = builder.build();
    }

    /** 获取登录号信息：{"user_id":123,"nickname":"xxx"} */
    public JsonNode getLoginInfo() {
        return call("get_login_info", Map.of());
    }

    /** 获取运行状态 */
    public JsonNode getStatus() {
        return call("get_status", Map.of());
    }

    /**
     * 根据消息 ID 取消息详情。
     *
     * <p>用途：QQ 的「引用/回复」消息段里只有一个消息 ID，没有正文，
     * 必须用这个接口把被引用的内容换回来。
     *
     * @param messageId 可以是短 ID（引用段里的 id）或真实消息 ID
     */
    public JsonNode getMsg(String messageId) {
        return call("get_msg", Map.of("message_id", messageId));
    }

    /** 发送群消息，返回 message_id */
    public long sendGroupMsg(long groupId, JsonNode message) {
        JsonNode data = call("send_group_msg", Map.of("group_id", groupId, "message", message));
        return data.path("message_id").asLong(0);
    }

    /** 发送私聊消息，返回 message_id */
    public long sendPrivateMsg(long userId, JsonNode message) {
        JsonNode data = call("send_private_msg", Map.of("user_id", userId, "message", message));
        return data.path("message_id").asLong(0);
    }

    /**
     * 发送**合并转发**（群聊）—— 把多条消息打包成一条「聊天记录」。
     *
     * <p>为什么用它：群里一条长回复会被切成很多段，逐条发就是很多次发言，
     * 很容易撞上群的发言频率限制；合并转发在群里只算**一次**发言。
     *
     * <p>⚠️ 动作名是 {@code send_group_forward_msg}（NapCat / go-cqhttp 都支持）。
     * 不支持时会抛 {@link OneBotApiException}，调用方退回逐条发送。
     *
     * @param nodes {@code {type:"node", data:{uin,name,content}}} 的数组
     */
    public long sendGroupForwardMsg(long groupId, JsonNode nodes) {
        JsonNode data = call("send_group_forward_msg",
                Map.of("group_id", groupId, "messages", nodes));
        return data.path("message_id").asLong(0);
    }

    /** 发送合并转发（私聊），动作名 {@code send_private_forward_msg} */
    public long sendPrivateForwardMsg(long userId, JsonNode nodes) {
        JsonNode data = call("send_private_forward_msg",
                Map.of("user_id", userId, "messages", nodes));
        return data.path("message_id").asLong(0);
    }

    /**
     * 调用任意 OneBot 动作。
     *
     * @param action 动作名，例如 send_group_msg、get_group_member_list
     * @param params 参数
     * @return 响应里的 data 部分
     */
    public JsonNode call(String action, Map<String, Object> params) {
        try {
            JsonNode response = restClient.post()
                    .uri("/" + action)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(params)
                    .retrieve()
                    .body(JsonNode.class);

            if (response == null) {
                throw new OneBotApiException(action + " 调用失败：响应为空");
            }
            if (!"ok".equals(response.path("status").asText())) {
                throw new OneBotApiException(action + " 调用失败：" + response);
            }
            log.debug("OneBot 动作 {} 成功", action);
            return response.path("data");
        } catch (RestClientException e) {
            throw new OneBotApiException(action + " 调用异常：" + e.getMessage(), e);
        }
    }
}
