package com.example.qqbot.command;

import com.example.qqbot.persistence.Jdbc;
import org.springframework.stereotype.Component;

import java.time.Instant;
/**
 * 全局 list / help 的自定义回复。
 *
 * <p>空内容表示继续用 CommandCatalog 自动生成的版本，避免把"默认文案"复制进库后
 * 命令表一变就过期。只有用户明确编辑过才写这一行。
 */
@Component
public class SystemReplyStore {

    public static final String LIST = "list";
    public static final String HELP = "help";

    private final Jdbc jdbc;

    public SystemReplyStore(Jdbc jdbc) {
        this.jdbc = jdbc;
        if (jdbc.isAvailable()) {
            jdbc.execute("CREATE TABLE IF NOT EXISTS bot_system_reply ("
                    + " key        TEXT PRIMARY KEY,"
                    + " content    TEXT NOT NULL,"
                    + " updated_at TEXT NOT NULL"
                    + " )");
        }
    }

    public String get(String key) {
        if (!jdbc.isAvailable()) {
            return "";
        }
        String content = jdbc.queryOne("SELECT content FROM bot_system_reply WHERE key = ?",
                r -> r.str("content"), key);
        return content == null ? "" : content;
    }

    public void save(String key, String content) {
        if (!LIST.equals(key) && !HELP.equals(key)) {
            throw new IllegalArgumentException("只能编辑全局 list / help");
        }
        String text = content == null ? "" : content.trim();
        jdbc.update("INSERT INTO bot_system_reply(key, content, updated_at) VALUES(?,?,?)"
                        + " ON CONFLICT(key) DO UPDATE SET content=excluded.content,"
                        + " updated_at=excluded.updated_at",
                key, text, Instant.now().toString());
    }
}
