package com.example.qqbot.kb.block;

import com.example.qqbot.config.PersistenceProperties;
import com.example.qqbot.config.QaProperties;
import com.example.qqbot.persistence.Jdbc;
import com.example.qqbot.persistence.KbBlockRepository;
import com.example.qqbot.persistence.QaStoreRepository;
import com.example.qqbot.persistence.SqliteDatabase;
import com.example.qqbot.qa.QaStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 块索引的**自动失效**验收（2026-10-06「激活协议」改造）。
 *
 * <p>钉住的性质只有一条：**写完块，不需要任何调用方记得 reload()**，
 * 索引自己就会看到新语料。以前是"写完请调用方记得调 reload()" ——
 * 导入器 / 地图派生 / 管理端一共 12 处手工调用，新增一个写入口就漏一个，
 * 而漏了的症状是**检索一直用旧语料、而且不报错**。
 *
 * <p>所以下面每个用例都**故意不调 {@code index.reload()}**：一旦有人把版本戳改回
 * "手动重载"，这些断言会立刻红。
 */
class KbBlockIndexTest {

    @TempDir
    Path base;

    private SqliteDatabase db;
    private KbBlockStore store;
    private KbBlockIndex index;

    @BeforeEach
    void setUp() {
        QaProperties qa = new QaProperties();
        PersistenceProperties persist = new PersistenceProperties();
        persist.setDb(base.resolve("qa.sqlite").toString());
        db = new SqliteDatabase(persist);
        db.init();
        QaStore qaStore = new QaStore(new QaStoreRepository(new Jdbc(db)), qa, new ObjectMapper());
        qaStore.init();

        store = new KbBlockStore(new KbBlockRepository(new Jdbc(db)));
        store.init();
        index = new KbBlockIndex(store);
    }

    @AfterEach
    void close() {
        db.close();
    }

    private static KbBlock block(String id, String body) {
        return new KbBlock(id, "doc", id + " 标题", body, "https://w/" + id,
                List.of("基础"), KbBlock.SRC_DOC, false, "");
    }

    private static float[] vec(float... v) {
        return v;
    }

    @Test
    @DisplayName("★ 写入后不调 reload()：索引立刻能看到（这是本次改造的全部目的）")
    void writeIsVisibleWithoutReload() {
        assertThat(index.entries()).isEmpty();

        store.upsert(block("a-0", "第一块正文"), vec(1f, 0f, 0f));

        assertThat(index.size()).isEqualTo(1);
        assertThat(index.block("a-0")).isNotNull();
        assertThat(index.block("a-0").body()).isEqualTo("第一块正文");
        assertThat(index.entries()).hasSize(1);
        assertThat(index.isReady()).isTrue();
    }

    @Test
    @DisplayName("覆盖同一块：正文改了，索引里就是新正文 —— 不用重启也不用 reload")
    void overwriteInvalidates() {
        store.upsert(block("a-0", "旧正文"), vec(1f, 0f, 0f));
        assertThat(index.block("a-0").body()).isEqualTo("旧正文");

        store.upsert(block("a-0", "新正文"), vec(1f, 0f, 0f));

        assertThat(index.size()).isEqualTo(1);
        assertThat(index.block("a-0").body()).isEqualTo("新正文");
    }

    @Test
    @DisplayName("下架 / 恢复 / 删除 / 清空：四种写操作都自己失效")
    void everyMutationInvalidates() {
        store.upsert(block("a-0", "正文"), vec(1f, 0f, 0f));
        assertThat(index.entries()).hasSize(1);
        assertThat(index.all()).hasSize(1);

        // 下架：立刻从索引里消失（索引只装未下架的块 —— 见 KbBlockStore.allActive 的 WHERE retired = 0）
        store.setRetired("a-0", true);
        assertThat(index.entries()).isEmpty();
        assertThat(index.all()).isEmpty();
        // 行本身还在，只是 retired=1 —— 所以这是"下架"而不是"删除"
        assertThat(store.all()).hasSize(1);
        assertThat(store.all().get(0).retired()).isTrue();

        // 恢复
        store.setRetired("a-0", false);
        assertThat(index.entries()).hasSize(1);
        assertThat(index.all()).hasSize(1);

        // 真删
        store.delete("a-0");
        assertThat(index.all()).isEmpty();
        assertThat(index.entries()).isEmpty();

        // 清空
        store.upsert(block("b-0", "另一块"), vec(0f, 1f, 0f));
        assertThat(index.entries()).hasSize(1);
        store.clearAll();
        assertThat(index.all()).isEmpty();
        assertThat(index.entries()).isEmpty();
    }

    @Test
    @DisplayName("标题向量也算一次写入 —— 补完索引立刻带上（C 路闸门靠它）")
    void titleVectorInvalidates() {
        store.upsert(block("a-0", "正文"), vec(1f, 0f, 0f));
        assertThat(index.titleVector("a-0")).isNull();
        assertThat(index.titleVectorCount()).isZero();

        store.upsertTitleVector("a-0", "a-0 标题", vec(0f, 1f, 0f));

        assertThat(index.titleVector("a-0")).isNotNull();
        assertThat(index.titleVectorCount()).isEqualTo(1);

        store.deleteTitleVector("a-0");
        assertThat(index.titleVector("a-0")).isNull();
    }

    @Test
    @DisplayName("版本号只在写成功之后前进；reload() 仍然能强制刷新")
    void versionAdvancesOnWriteOnly() {
        long v0 = store.version();
        // 读不改变版本
        index.entries();
        index.all();
        assertThat(store.version()).isEqualTo(v0);

        store.upsert(block("a-0", "正文"), vec(1f, 0f, 0f));
        assertThat(store.version()).isGreaterThan(v0);

        // 写失败（不存在的块做下架/删除）不该动版本
        long v1 = store.version();
        assertThat(store.setRetired("no-such-block", true)).isFalse();
        assertThat(store.delete("no-such-block")).isFalse();
        assertThat(store.version()).isEqualTo(v1);

        // 强制重载仍然可用（CLI / 测试逃生口）
        assertThat(index.reload()).isTrue();
        assertThat(index.size()).isEqualTo(1);
    }
}
