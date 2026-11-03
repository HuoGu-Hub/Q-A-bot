package com.example.qqbot.eval;

import com.example.qqbot.config.PersistenceProperties;
import com.example.qqbot.persistence.EvalRepository;
import com.example.qqbot.persistence.Jdbc;
import com.example.qqbot.persistence.SqliteDatabase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 迁移只在评测库为空时发生，且按列交集搬：老库缺的新列用默认值。
 */
class EvalDbMigrationTest {

    private static SqliteDatabase open(Path file) {
        PersistenceProperties props = new PersistenceProperties();
        props.setDb(file.toString());
        SqliteDatabase db = new SqliteDatabase(props);
        db.init();
        return db;
    }

    @Test
    @DisplayName("主库有历史时搬进空评测库，且只搬一次")
    void migratesOnce() throws Exception {
        Path dir = Files.createTempDirectory("eval-migrate-");
        Path mainFile = dir.resolve("main.sqlite");
        Path evalFile = dir.resolve("eval.sqlite");

        SqliteDatabase main = open(mainFile);
        try {
            EvalRepository mainRepo = new EvalRepository(new Jdbc(main));
            mainRepo.initSchema();
            long datasetId = mainRepo.insertDataset("golden", "检索黄金集", "", "test",
                    "2026-11-03T10:00:00Z");
            mainRepo.insertBatch("批次一", datasetId, 1, "{}", "offline", 2, 1, 1.0, null,
                    "test", "2026-11-03T10:00:00Z");
            assertThat(mainRepo.listBatches(10, 0)).hasSize(1);
        } finally {
            main.close();
        }

        SqliteDatabase eval = open(evalFile);
        try {
            Jdbc evalJdbc = new Jdbc(eval);
            EvalRepository evalRepo = new EvalRepository(evalJdbc);
            EvalDbMigration migration = new EvalDbMigration(evalJdbc, evalRepo, mainFile.toString());
            migration.run(null);

            assertThat(evalRepo.listBatches(10, 0)).hasSize(1);
            assertThat(evalRepo.listBatches(10, 0).get(0).name()).isEqualTo("批次一");

            migration.run(null);
            assertThat(evalRepo.listBatches(10, 0)).hasSize(1);
        } finally {
            eval.close();
        }
    }
}
