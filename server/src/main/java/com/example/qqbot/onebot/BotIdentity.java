package com.example.qqbot.onebot;

import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 保存「机器人自己的 QQ 号 + 昵称」。
 * 启动时通过 get_login_info 一起拿到，用于判断群里有没有 @ 自己、抑制消息回环，
 * 以及给**合并转发**的每个节点署名（那条聊天记录里"谁说的"）。
 */
@Component
public class BotIdentity {

    private final AtomicLong selfId = new AtomicLong(0);

    /** 昵称，可能是空串（启动时没连上协议层） */
    private final AtomicReference<String> selfName = new AtomicReference<>("");

    public long getSelfId() {
        return selfId.get();
    }

    public void setSelfId(long id) {
        selfId.set(id);
    }

    public String getSelfName() {
        return selfName.get();
    }

    public void setSelfName(String name) {
        selfName.set(name == null ? "" : name);
    }

    /** 判断某个 user_id 是不是机器人自己 */
    public boolean isSelf(Long userId) {
        return userId != null && userId == selfId.get();
    }
}
