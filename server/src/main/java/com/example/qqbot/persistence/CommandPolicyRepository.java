package com.example.qqbot.persistence;

import com.example.qqbot.command.DataPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;

/**
 * 命令数据策略表（{@code bot_command_policy}）。
 *
 * <p>单独一张表而不是加在 {@code bot_command} 上：策略是后加的维度，
 * 老库里没有它应当是"默认本群"，而不是"字段为空需要处处判空"。
 * 没有行 = 用默认值，这条规则让升级不需要任何数据迁移。
 */
@Repository
public class CommandPolicyRepository {

    private static final Logger log = LoggerFactory.getLogger(CommandPolicyRepository.class);

    /** 一行策略 */
    public record Row(String scope, String groupsJson, boolean showGroup, boolean showUser,
                      boolean allowPrivate) {
    }

    private final Jdbc jdbc;

    public CommandPolicyRepository(Jdbc jdbc) {
        this.jdbc = jdbc;
    }

    public boolean isAvailable() {
        return jdbc.isAvailable();
    }

    public void initSchema() {
        jdbc.execute("CREATE TABLE IF NOT EXISTS bot_command_policy ("
                + " command_id       INTEGER PRIMARY KEY,"
                + " data_scope       TEXT NOT NULL DEFAULT 'GROUP',"
                + " data_groups      TEXT NOT NULL DEFAULT '[]',"
                + " show_origin_group INTEGER NOT NULL DEFAULT 0,"
                + " show_origin_user  INTEGER NOT NULL DEFAULT 0,"
                + " allow_private     INTEGER NOT NULL DEFAULT 0"
                + " )");
        // 老库补列：私聊开关是后加的，没有它的行按"不开放"算
        List<String> cols = jdbc.query("PRAGMA table_info(bot_command_policy)", r -> r.str("name"));
        if (!cols.contains("allow_private")) {
            jdbc.execute("ALTER TABLE bot_command_policy"
                    + " ADD COLUMN allow_private INTEGER NOT NULL DEFAULT 0");
        }
    }

    public Row find(long commandId) {
        return jdbc.queryOne("SELECT data_scope, data_groups, show_origin_group, show_origin_user,"
                + " allow_private FROM bot_command_policy WHERE command_id = ?",
                r -> new Row(r.str("data_scope"), r.str("data_groups"),
                        r.intOf("show_origin_group") == 1, r.intOf("show_origin_user") == 1,
                        r.intOf("allow_private") == 1),
                commandId);
    }

    /** 整份覆盖；全默认时删掉这一行，保持"没有行 = 默认" */
    public void save(long commandId, Row row) {
        jdbc.update("INSERT INTO bot_command_policy"
                        + " (command_id, data_scope, data_groups, show_origin_group, show_origin_user,"
                        + "  allow_private) VALUES (?,?,?,?,?,?)"
                        + " ON CONFLICT(command_id) DO UPDATE SET"
                        + "  data_scope=excluded.data_scope, data_groups=excluded.data_groups,"
                        + "  show_origin_group=excluded.show_origin_group,"
                        + "  show_origin_user=excluded.show_origin_user,"
                        + "  allow_private=excluded.allow_private",
                commandId, row.scope(), row.groupsJson(),
                row.showGroup() ? 1 : 0, row.showUser() ? 1 : 0, row.allowPrivate() ? 1 : 0);
    }

    public void delete(long commandId) {
        jdbc.update("DELETE FROM bot_command_policy WHERE command_id = ?", commandId);
    }

    /** 总览页要一次拿到所有命令的策略 */
    public List<Long> commandsWithPolicy() {
        List<Long> out = new ArrayList<>();
        for (long id : jdbc.query("SELECT command_id FROM bot_command_policy",
                r -> r.longOf("command_id"))) {
            out.add(id);
        }
        return out;
    }
}
