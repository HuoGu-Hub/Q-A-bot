package com.example.qqbot.onebot.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

/**
 * OneBot 11 事件（只声明我们用得到的字段，其余靠 ignoreUnknown 忽略）。
 *
 * <p>字段名用蛇形（post_type / group_id ...），靠 @JsonNaming 自动映射，
 * 所以 Java 这边仍然是驼峰。
 *
 * <p>注意 message 字段：NapCat 的 messagePostFormat 配成 array 时是「消息段数组」，
 * 配成 string 时是纯文本。两种都要能处理，所以这里先存成 JsonNode，交给 MessageCodec 解析。
 *
 * <p>真实样本（从 NapCat 日志里抓的）：
 * <pre>
 * {
 *   "post_type": "message", "message_type": "group",
 *   "self_id": 100000003, "user_id": 100000001, "group_id": 100000002,
 *   "message_id": 100000006,
 *   "sender": { "user_id": 100000001, "nickname": "示例作者", "role": "owner" },
 *   "raw_message": "[CQ:at,qq=100000003] bot通信测试",
 *   "message": [ { "type": "at", "data": { "qq": "100000003" } },
 *                { "type": "text", "data": { "text": " bot通信测试" } } ]
 * }
 * </pre>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class OneBotEvent {

    /** message / notice / request / meta_event */
    private String postType;

    /** private / group */
    private String messageType;

    private String subType;

    private Long messageId;
    private Long userId;
    private Long groupId;
    private Long selfId;
    private Long time;

    private String rawMessage;
    private JsonNode message;
    private JsonNode sender;

    /** notice 事件用 */
    private String noticeType;
    /** request 事件用 */
    private String requestType;
    /** meta_event 用 */
    private String metaEventType;

    @JsonIgnore
    public boolean isMessageEvent() {
        return "message".equals(postType) || "message_sent".equals(postType);
    }

    @JsonIgnore
    public boolean isGroupMessage() {
        return isMessageEvent() && "group".equals(messageType);
    }

    @JsonIgnore
    public boolean isPrivateMessage() {
        return isMessageEvent() && "private".equals(messageType);
    }

    public String getPostType() {
        return postType;
    }

    public void setPostType(String postType) {
        this.postType = postType;
    }

    public String getMessageType() {
        return messageType;
    }

    public void setMessageType(String messageType) {
        this.messageType = messageType;
    }

    public String getSubType() {
        return subType;
    }

    public void setSubType(String subType) {
        this.subType = subType;
    }

    public Long getMessageId() {
        return messageId;
    }

    public void setMessageId(Long messageId) {
        this.messageId = messageId;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public Long getGroupId() {
        return groupId;
    }

    public void setGroupId(Long groupId) {
        this.groupId = groupId;
    }

    public Long getSelfId() {
        return selfId;
    }

    public void setSelfId(Long selfId) {
        this.selfId = selfId;
    }

    public Long getTime() {
        return time;
    }

    public void setTime(Long time) {
        this.time = time;
    }

    public String getRawMessage() {
        return rawMessage;
    }

    public void setRawMessage(String rawMessage) {
        this.rawMessage = rawMessage;
    }

    public JsonNode getMessage() {
        return message;
    }

    public void setMessage(JsonNode message) {
        this.message = message;
    }

    public JsonNode getSender() {
        return sender;
    }

    public void setSender(JsonNode sender) {
        this.sender = sender;
    }

    public String getNoticeType() {
        return noticeType;
    }

    public void setNoticeType(String noticeType) {
        this.noticeType = noticeType;
    }

    public String getRequestType() {
        return requestType;
    }

    public void setRequestType(String requestType) {
        this.requestType = requestType;
    }

    public String getMetaEventType() {
        return metaEventType;
    }

    public void setMetaEventType(String metaEventType) {
        this.metaEventType = metaEventType;
    }

    @Override
    public String toString() {
        return "OneBotEvent{postType=" + postType
                + ", messageType=" + messageType
                + ", groupId=" + groupId
                + ", userId=" + userId
                + ", messageId=" + messageId + "}";
    }
}
