package com.example.qqbot.persistence;

import com.example.qqbot.command.AclEntry;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;

/**
 * 名单表（{@code bot_acl}）的数据访问。
 *
 * <h2>为什么全局名单也在这张表里</h2>
 * {@code command_id = 0} 表示"对所有命令生效"。多一张表就要多一套增删改查，
 * 而两者的形状一模一样 —— 差别只在"这一行管谁"，用一列表达就够了。
 *
 * <p>主键把重复项直接挡掉：同一个人被加两次白名单，写进去仍然只有一行。
 */
@Repository
public class AclRepository {

    /** 全局名单借用的命令 id */
    private static final long GLOBAL = AclEntry.GLOBAL;

    private final Jdbc jdbc;

    public AclRepository(Jdbc jdbc) {
        this.jdbc = jdbc;
    }

    public boolean isAvailable() {
        return jdbc.isAvailable();
    }

    public void initSchema() {
        jdbc.execute("CREATE TABLE IF NOT EXISTS bot_acl ("
                + " command_id INTEGER NOT NULL,"
                + " scope      TEXT NOT NULL,"
                + " kind       TEXT NOT NULL,"
                + " value      INTEGER NOT NULL,"
                + " PRIMARY KEY (command_id, scope, kind, value))");
    }

    /** 某条命令自己的名单；传 0 就是全局名单 */
    public List<AclEntry> list(long commandId) {
        return jdbc.query("SELECT scope, kind, value FROM bot_acl WHERE command_id = ?"
                        + " ORDER BY scope, kind, value",
                r -> new AclEntry(r.str("scope"), r.str("kind"), r.longOf("value")),
                commandId);
    }

    /**
     * 整份替换。
     *
     * <p>用事务包住"先删后插"：中间被打断会留下一份半截名单，
     * 而名单是准入判定，半截意味着某些人意外获得或失去权限。
     */
    public void replace(long commandId, List<AclEntry> entries) {
        jdbc.transaction(() -> {
            jdbc.update("DELETE FROM bot_acl WHERE command_id = ?", commandId);
            if (entries != null && !entries.isEmpty()) {
                List<Object[]> rows = new ArrayList<>(entries.size());
                for (AclEntry entry : entries) {
                    rows.add(new Object[]{commandId, entry.scope(), entry.type(), entry.value()});
                }
                jdbc.batch("INSERT OR IGNORE INTO bot_acl (command_id, scope, kind, value)"
                        + " VALUES (?, ?, ?, ?)", rows);
            }
            return null;
        });
    }

    /** 命令被删掉时把它的名单一起清掉，不留孤儿行 */
    public void delete(long commandId) {
        jdbc.update("DELETE FROM bot_acl WHERE command_id = ?", commandId);
    }

    public long globalCommandId() {
        return GLOBAL;
    }
}
