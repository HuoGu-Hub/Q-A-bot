package com.example.qqbot.onebot.codec;

import com.example.qqbot.onebot.model.ImageRef;
import com.example.qqbot.onebot.model.OneBotEvent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * OneBot 消息段的解析与构造。
 *
 * <p>这里是唯一允许出现 OneBot 消息段细节的地方。业务代码不要直接碰 JsonNode。
 *
 * <p>目前支持的消息段：
 * <ul>
 *   <li>{@code text} —— 纯文本</li>
 *   <li>{@code at} —— @某人</li>
 *   <li>{@code face} —— QQ 自带表情（没有文字内容）</li>
 *   <li>{@code image} —— 图片，带 url</li>
 *   <li>{@code reply} —— 引用/回复，只有消息 ID，正文要另外调 get_msg 去取</li>
 * </ul>
 */
@Component
public class MessageCodec {

    public static final String TYPE_TEXT = "text";
    public static final String TYPE_AT = "at";
    public static final String TYPE_IMAGE = "image";
    /** 合并转发的节点段（只在构造 forwardNodes 时用，收到的消息里不会有它） */
    public static final String TYPE_NODE = "node";
    public static final String TYPE_FACE = "face";
    public static final String TYPE_REPLY = "reply";
    public static final String TYPE_VOICE = "record";

    /** QQ 用图片内容的 MD5 给文件命名，形如 65A82BE1AE2810AEB287D78C2260C823.jpg */
    private static final Pattern MD5_FILE_NAME =
            Pattern.compile("^([0-9A-Fa-f]{32})(?:\\.[A-Za-z0-9]{1,8})?$");

    /** 老式链接里的 MD5：https://gchat.qpic.cn/gchatpic_new/0/0-0-<MD5>/0 */
    private static final Pattern MD5_IN_URL =
            Pattern.compile("gchatpic_new/[^/]+/[^/]*?-([0-9A-Fa-f]{32})(?:/|$)");

    private final ObjectMapper mapper;

    public MessageCodec(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    // ==================== 解析 ====================

    /** 从事件里抽出纯文本内容（图片、表情等非文本段会被忽略） */
    public String extractPlainText(OneBotEvent event) {
        return extractPlainText(event.getMessage());
    }

    /** 从任意 message 节点抽纯文本（get_msg 的返回也用它） */
    public String extractPlainText(JsonNode message) {
        if (message == null || message.isNull()) {
            return "";
        }
        // messagePostFormat = string 的情况：直接就是纯文本（可能带 CQ 码）
        if (message.isTextual()) {
            return message.asText().trim();
        }
        if (!message.isArray()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (JsonNode segment : message) {
            if (TYPE_TEXT.equals(segment.path("type").asText())) {
                sb.append(segment.path("data").path("text").asText(""));
            }
        }
        return sb.toString().trim();
    }

    /**
     * 取出引用（回复）消息段里的消息 ID。
     *
     * <p>QQ 的引用消息**不包含被引用消息的正文**，只有一个 ID，
     * 需要另外调 OneBot 的 get_msg 去换内容。
     *
     * <p>优先用 id（短 ID 映射，专为这个场景设计）；没有就退回 seq。
     */
    public Optional<String> extractReplyId(OneBotEvent event) {
        JsonNode message = event.getMessage();
        if (message == null || !message.isArray()) {
            return Optional.empty();
        }
        for (JsonNode segment : message) {
            if (!TYPE_REPLY.equals(segment.path("type").asText())) {
                continue;
            }
            JsonNode data = segment.path("data");
            String id = data.path("id").asText("");
            if (StringUtils.hasText(id)) {
                return Optional.of(id);
            }
            long seq = data.path("seq").asLong(0);
            if (seq > 0) {
                return Optional.of(String.valueOf(seq));
            }
        }
        return Optional.empty();
    }

    /** 取出消息里所有图片的 URL（file 字段是 http 链接时也认） */
    public List<String> extractImageUrls(OneBotEvent event) {
        return extractImageUrls(event.getMessage());
    }

    public List<String> extractImageUrls(JsonNode message) {
        List<String> urls = new ArrayList<>();
        for (ImageRef ref : extractImageRefs(message)) {
            urls.add(ref.url());
        }
        return urls;
    }

    /**
     * 取出消息里所有图片的**完整引用信息**（含缓存键）。
     *
     * <p>缓存键来自 {@code file} 字段 —— QQ 用图片内容的 MD5 给文件命名，
     * 所以这个值在下载之前就能拿到，且同一张图永远相同。
     * 取不到时 {@code key} 为 null（退化成不缓存，仍然能正常理解图片）。
     */
    public List<ImageRef> extractImageRefs(OneBotEvent event) {
        return extractImageRefs(event.getMessage());
    }

    public List<ImageRef> extractImageRefs(JsonNode message) {
        List<ImageRef> refs = new ArrayList<>();
        if (message == null || !message.isArray()) {
            return refs;
        }
        for (JsonNode segment : message) {
            if (!TYPE_IMAGE.equals(segment.path("type").asText())) {
                continue;
            }
            JsonNode data = segment.path("data");
            String file = data.path("file").asText("");
            String url = data.path("url").asText("");
            if (!StringUtils.hasText(url)
                    && (file.startsWith("http://") || file.startsWith("https://"))) {
                url = file;
            }
            if (!StringUtils.hasText(url)) {
                continue;
            }
            String key = md5KeyFromFileName(file);
            if (key == null) {
                key = md5KeyFromUrl(url);
            }
            refs.add(new ImageRef(key, url, data.path("file_size").asLong(0)));
        }
        return refs;
    }

    /**
     * 从图片文件名里取 MD5。QQ 的命名形如 {@code 65A82BE1...C823.jpg}。
     *
     * @return 小写 32 位 MD5；文件名不是这个形状时返回 null
     */
    private String md5KeyFromFileName(String fileName) {
        if (!StringUtils.hasText(fileName)) {
            return null;
        }
        String name = fileName.replace('\\', '/');
        int slash = name.lastIndexOf('/');
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        Matcher m = MD5_FILE_NAME.matcher(name);
        return m.matches() ? m.group(1).toLowerCase(Locale.ROOT) : null;
    }

    /** 兜底：部分链接形如 https://gchat.qpic.cn/gchatpic_new/0/0-0-<MD5>/0 */
    private String md5KeyFromUrl(String url) {
        if (!StringUtils.hasText(url)) {
            return null;
        }
        Matcher m = MD5_IN_URL.matcher(url);
        return m.find() ? m.group(1).toLowerCase(Locale.ROOT) : null;
    }

    /** 消息里有没有图片 */
    public boolean hasImage(OneBotEvent event) {
        return !extractImageUrls(event).isEmpty();
    }

    /** 群里有没有 @ 机器人自己 */
    public boolean isMentioned(OneBotEvent event, long selfId) {
        JsonNode message = event.getMessage();
        if (message == null || !message.isArray()) {
            return false;
        }
        for (JsonNode segment : message) {
            if (TYPE_AT.equals(segment.path("type").asText())
                    && String.valueOf(selfId).equals(segment.path("data").path("qq").asText())) {
                return true;
            }
        }
        return false;
    }

    // ==================== 构造 ====================

    /** 构造一条纯文本消息（用于私聊回复） */
    public JsonNode textMessage(String text) {
        ArrayNode array = mapper.createArrayNode();
        array.add(segment(TYPE_TEXT, Map.of("text", text)));
        return array;
    }

    /** 构造「@某人 + 文本」的消息（用于群聊回复，更自然） */
    public JsonNode atPlusText(Long userId, String text) {
        ArrayNode array = mapper.createArrayNode();
        array.add(segment(TYPE_AT, Map.of("qq", String.valueOf(userId))));
        array.add(segment(TYPE_TEXT, Map.of("text", " " + text)));
        return array;
    }

    /**
     * 构造一条**合并转发**（QQ 的「聊天记录」）的节点数组。
     *
     * <p>{@code content} 复用 {@link #textMessage} —— 和普通消息同一套段格式，
     * 免得这里再长出第二份"怎么拼文本段"的知识。
     *
     * <p><b>为什么要同时写两套署名字段</b>：这块没有统一标准，各家实现认的不一样 ——
     * <ul>
     *   <li>NapCat 认 {@code user_id}（而且 PacketServer 模式下**只接受数字**）+ {@code nickname}；</li>
     *   <li>go-cqhttp / Lagrange 认 {@code uin} + {@code name}（字符串）。</li>
     * </ul>
     * 两套都写上，各认各的，多余的字段会被忽略。只写一套的话换个协议端就"节点为空"。
     *
     * @param parts 已经切好的文本段（每条 node 一段）
     * @param uin   机器人自己的 QQ 号
     * @param name  机器人自己的昵称
     */
    public JsonNode forwardNodes(List<String> parts, long uin, String name) {
        String nick = name == null ? "" : name;
        ArrayNode nodes = mapper.createArrayNode();
        for (String part : parts) {
            ObjectNode node = mapper.createObjectNode();
            node.put("type", TYPE_NODE);
            ObjectNode data = node.putObject("data");
            data.put("user_id", uin);                  // NapCat：数字，不能是字符串
            data.put("nickname", nick);
            data.put("uin", String.valueOf(uin));      // go-cqhttp / Lagrange 兼容
            data.put("name", nick);
            data.set("content", textMessage(part));
            nodes.add(node);
        }
        return nodes;
    }

    private ObjectNode segment(String type, Map<String, Object> data) {
        ObjectNode node = mapper.createObjectNode();
        node.put("type", type);
        // 保持字段顺序稳定，方便排查日志
        node.set("data", mapper.valueToTree(new LinkedHashMap<>(data)));
        return node;
    }
}
