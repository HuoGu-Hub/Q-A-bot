package com.example.qqbot.eval;

import com.example.qqbot.persistence.EvalRepository;
import com.example.qqbot.persistence.Jdbc;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 把主库里的评测历史搬到独立评测库。
 *
 * <p>只在评测库**一条批次都没有**时搬一次：已经有数据说明要么搬过、要么是新库开始跑过了，
 * 再搬会把两边的 id 撞在一起。搬完保留主库原表不动，出问题还能回头查。
 *
 * <p>用 {@code ATTACH} 把主库挂成 {@code old}，按两边的列交集搬 —— 老库缺的新列
 * 交给默认值，不因为加过列就整条跳过。
 */
@Component
@Order(0)
public class EvalDbMigration implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(EvalDbMigration.class);

    /** 迁移顺序按外键依赖排，子表在后 */
    private static final List<String> TABLES = List.of(
            "eval_dataset", "eval_dataset_version", "eval_case", "eval_batch", "eval_item",
            "eval_run", "eval_span", "eval_retrieval", "eval_judge", "eval_failure",
            "eval_tool_call", "eval_citation", "eval_review_task", "eval_baseline", "eval_price",
            "eval_alert_rule", "eval_alert_event", "eval_calibration");

    private final Jdbc jdbc;
    private final EvalRepository repo;
    private final String mainDb;

    public EvalDbMigration(@Qualifier("evalJdbc") Jdbc jdbc, EvalRepository repo,
                           @Value("${app.persistence.db:}") String mainDb) {
        this.jdbc = jdbc;
        this.repo = repo;
        this.mainDb = mainDb;
    }

    @Override
    public void run(ApplicationArguments args) {
        repo.initSchema();
        if (mainDb == null || mainDb.isBlank()) {
            return;
        }
        Path main = Paths.get(mainDb).toAbsolutePath().normalize();
        if (!Files.exists(main) || main.toString().equals(evalPath())) {
            return;
        }
        if (count("eval_batch") > 0) {
            return;
        }
        jdbc.execute("ATTACH DATABASE '" + main.toString().replace("'", "''") + "' AS old");
        try {
            if (!oldHasTable("eval_batch")) {
                return;
            }
            int moved = 0;
            for (String table : TABLES) {
                moved += copy(table);
            }
            log.info("[EVAL] 评测库已从主库迁移 {} 行（主库原表保留）", moved);
        } catch (RuntimeException e) {
            log.warn("[EVAL] 评测库迁移失败：{}", e.getMessage());
        } finally {
            jdbc.execute("DETACH DATABASE old");
        }
    }

    private int copy(String table) {
        Set<String> target = columns("PRAGMA table_info(" + table + ")");
        Set<String> source = columns("PRAGMA old.table_info(" + table + ")");
        List<String> common = new ArrayList<>(target);
        common.retainAll(source);
        if (common.isEmpty()) {
            return 0;
        }
        String cols = String.join(",", common);
        return jdbc.update("INSERT OR IGNORE INTO " + table + " (" + cols + ")"
                + " SELECT " + cols + " FROM old." + table);
    }

    private Set<String> columns(String pragma) {
        return new LinkedHashSet<>(jdbc.query(pragma, rs -> rs.str("name")));
    }

    private boolean oldHasTable(String table) {
        return !jdbc.query("SELECT name FROM old.sqlite_master WHERE type='table' AND name=?",
                rs -> rs.str("name"), table).isEmpty();
    }

    private long count(String table) {
        Long n = jdbc.queryOne("SELECT COUNT(*) AS c FROM " + table, rs -> rs.longOf("c"));
        return n == null ? 0 : n;
    }

    /** 当前连接打开的主文件；与主库同路径时说明没拆开，迁移没有意义 */
    private String evalPath() {
        List<String> files = jdbc.query("PRAGMA database_list", rs -> rs.str("file"));
        return files.isEmpty() || files.get(0) == null ? "" : files.get(0);
    }
}
