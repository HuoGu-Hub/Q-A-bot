package com.example.qqbot.config;

import com.example.qqbot.persistence.Jdbc;
import com.example.qqbot.persistence.SqliteDatabase;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 评测库 —— 独立的一个 SQLite 文件，不再和群问答抢同一个写通道。
 *
 * <p>默认放在主库同目录的 {@code eval.sqlite}；要换位置用 {@code app.eval.db}。
 * 关库开关跟随主库：主库关了，评测也不开库。
 *
 * <p>{@code @Primary} 标在主库两个类上，所以现有仓储照旧注入主库；
 * 只有 {@code EvalRepository} 显式点名 {@code evalJdbc}。
 */
@Configuration
public class EvalDatabaseConfig {

    @Bean(name = "evalSqlite", destroyMethod = "close")
    public SqliteDatabase evalSqlite(@Value("${app.eval.db:}") String configured,
                                     PersistenceProperties main) {
        String path = configured == null ? "" : configured.strip();
        if (path.isEmpty()) {
            path = sibling(main.getDb());
        }
        PersistenceProperties props = new PersistenceProperties();
        props.setDb(path);
        props.setEnabled(main.isEnabled());
        SqliteDatabase db = new SqliteDatabase(props);
        db.init();
        return db;
    }

    @Bean(name = "evalJdbc")
    public Jdbc evalJdbc(@Qualifier("evalSqlite") SqliteDatabase db) {
        return new Jdbc(db);
    }

    static String sibling(String mainDb) {
        Path main = Paths.get(mainDb).toAbsolutePath().normalize();
        Path dir = main.getParent();
        return dir == null ? "eval.sqlite" : dir.resolve("eval.sqlite").toString();
    }
}
