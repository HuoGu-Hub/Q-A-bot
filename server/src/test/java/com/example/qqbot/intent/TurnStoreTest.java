package com.example.qqbot.intent;

import com.example.qqbot.config.PersistenceProperties;
import com.example.qqbot.persistence.Jdbc;
import com.example.qqbot.persistence.SqliteDatabase;
import com.example.qqbot.persistence.TurnRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TurnStoreTest {

    @TempDir
    Path base;

    private SqliteDatabase db;
    private TurnRepository repo;
    private TurnStore store;

    private static IntentPolicy policy(int turns, int minutes) {
        return new IntentPolicy() {
            @Override
            public boolean isEnabled() {
                return true;
            }

            @Override
            public boolean isSkipSocial() {
                return true;
            }

            @Override
            public boolean isSkipMeta() {
                return true;
            }

            @Override
            public boolean isSkipOutOfScope() {
                return true;
            }

            @Override
            public boolean isCorrection() {
                return true;
            }

            @Override
            public int getRecentTurns() {
                return turns;
            }

            @Override
            public int getRecentMinutes() {
                return minutes;
            }
        };
    }

    @BeforeEach
    void setUp() {
        PersistenceProperties persist = new PersistenceProperties();
        persist.setDb(base.resolve("turn.sqlite").toString());
        db = new SqliteDatabase(persist);
        db.init();
        repo = new TurnRepository(new Jdbc(db));
        store = new TurnStore(repo, policy(3, 30));
    }

    @AfterEach
    void close() {
        db.close();
    }

    @Test
    @DisplayName("记一轮之后能读回来，新的在前")
    void appendThenRecent() {
        store.append(1L, 100L, "现在技能点上限是多少", "目前是 10 点，满级后还能再加");
        store.append(1L, 100L, "那 185 呢", "185 是带上装备之后的算法");

        List<TurnStore.Turn> turns = store.recent(1L, 100L);
        assertThat(turns).hasSize(2);
        assertThat(turns.get(0).question()).isEqualTo("那 185 呢");
        assertThat(turns.get(0).answerDigest()).contains("185");
    }

    @Test
    @DisplayName("别人的记忆不会串到这个人身上")
    void isolatedPerUserAndGroup() {
        store.append(1L, 100L, "香菇在哪刷新", "长守镇东侧");
        assertThat(store.recent(1L, 200L)).isEmpty();
        assertThat(store.recent(2L, 100L)).isEmpty();
    }

    @Test
    @DisplayName("只留最近几轮，更早的自己被挤掉")
    void trimsToRecentTurns() {
        for (int i = 1; i <= 5; i++) {
            store.append(1L, 100L, "问题" + i, "回答" + i);
        }
        assertThat(store.recent(1L, 100L)).hasSize(3);
        assertThat(repo.countAll()).isEqualTo(3);
    }

    @Test
    @DisplayName("超过窗口时间就当没有 —— 半小时前的对话不该被当成上下文")
    void staleTurnsIgnored() {
        repo.insert(1L, 100L, Instant.now().minus(2, ChronoUnit.HOURS).toString(), "很久以前", "旧回答");
        assertThat(store.recent(1L, 100L)).isEmpty();
    }

    @Test
    @DisplayName("摘要只取前 60 字，且压成一行")
    void digestIsShort() {
        String longAnswer = "甲".repeat(200) + "\n第二段";
        String digest = TurnStore.digest(longAnswer);
        assertThat(digest.length()).isLessThanOrEqualTo(61);
        assertThat(digest).doesNotContain("\n");
        assertThat(TurnStore.digest(null)).isEmpty();
    }

    @Test
    @DisplayName("标记纠正落在上一轮，不在下一轮")
    void markCorrectedHitsPreviousTurn() {
        store.append(1L, 100L, "技能点上限是多少", "10 点");
        store.markCorrected(1L, 100L);
        store.append(1L, 100L, "不是 185 吗", "你说得对");
        List<TurnRepository.Row> rows = repo.recent(1L, 100L,
                Instant.now().minus(1, ChronoUnit.DAYS).toString(), 5);
        assertThat(rows.get(1).corrected()).as("被纠正的是上一轮").isTrue();
        assertThat(rows.get(0).corrected()).as("本轮不该被标").isFalse();
    }

    @Test
    @DisplayName("清理只删过期的")
    void deleteOlderThan() {
        repo.insert(1L, 100L, Instant.now().minus(9, ChronoUnit.DAYS).toString(), "九天前", "旧");
        store.append(1L, 100L, "刚刚", "新的");
        assertThat(repo.deleteOlderThan(Instant.now().minus(7, ChronoUnit.DAYS).toString())).isEqualTo(1);
        assertThat(repo.countAll()).isEqualTo(1);
    }
}
