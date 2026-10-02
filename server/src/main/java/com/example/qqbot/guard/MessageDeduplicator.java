package com.example.qqbot.guard;

import com.example.qqbot.onebot.model.OneBotEvent;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 消息去重。
 *
 * <p>说明：NapCat 的 HTTP 上报实际上是「发一次就不管」，不会超时重发，
 * 所以正常情况下不会有重复。这里保留一层内存去重，主要是防止调试时手动重放同一个
 * message_id 导致机器人回复两遍。成本极低，做成保险。
 *
 * <p>用内存 LRU 实现，重启即失效——对当前用途足够了。
 */
@Component
public class MessageDeduplicator {

    private static final int MAX_ENTRIES = 5000;

    private final Map<String, Long> seen = Collections.synchronizedMap(
            new LinkedHashMap<>(256, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Long> eldest) {
                    return size() > MAX_ENTRIES;
                }
            });

    /**
     * @return true 表示这条事件之前处理过，应当忽略
     */
    public boolean isDuplicate(OneBotEvent event) {
        String key = buildKey(event);
        if (key == null) {
            return false;
        }
        return seen.put(key, System.currentTimeMillis()) != null;
    }

    private String buildKey(OneBotEvent event) {
        if (event.getMessageId() != null) {
            return "msg:" + event.getMessageId();
        }
        // 没有 message_id 的兜底：用「谁 + 什么时候 + 说了什么」拼一个指纹
        if (event.getTime() == null || event.getUserId() == null) {
            return null;
        }
        return "fp:" + event.getSelfId() + ":" + event.getUserId() + ":" + event.getTime()
                + ":" + event.getRawMessage();
    }

    /** 供测试使用 */
    public void clear() {
        seen.clear();
    }
}
