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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 块存储的验收测试。
 *
 * <p>它钉的是**这次改造换来的能力**：id 就是身份，
 * 于是"删除"和"改 id"这两件旧设计做不到的事，现在能做了。
 */
class KbBlockStoreTest {

    @TempDir
    Path base;

    private QaStore qaStore;
    private SqliteDatabase db;
    private KbBlockStore store;

    @BeforeEach
    void setUp() {
        QaProperties qa = new QaProperties();
        PersistenceProperties persist = new PersistenceProperties();
        persist.setDb(base.resolve("qa.sqlite").toString());
        db = new SqliteDatabase(persist);
        db.init();
        qaStore = new QaStore(new QaStoreRepository(new Jdbc(db)), qa, new ObjectMapper());
        qaStore.init();

        store = new KbBlockStore(new KbBlockRepository(new Jdbc(db)));
        store.init();
        assertThat(store.isAvailable()).isTrue();
    }

    private static KbBlock block(String id, String docId, String title, String body) {
        return new KbBlock(id, docId, title, body, "https://w/" + id,
                List.of("基础", "据点"), KbBlock.SRC_DOC, false, "");
    }

    private static float[] vec(float... v) {
        return v;
    }

    /* ==================== 基本读写 ==================== */

    @AfterEach
    void closeStores() {
        db.close();
    }

    @Test
    @DisplayName("写入后原样读回：id / 文档 / 标题 / 正文 / url / 标签 / 来源")
    void upsertAndGetRoundTrip() {
        boolean isNew = store.upsert(block("flame-altar-0", "flame-altar", "灵火祭坛", "祭坛是复活点"), vec(1, 0, 0));

        assertThat(isNew).isTrue();
        KbBlock b = store.get("flame-altar-0");
        assertThat(b).isNotNull();
        assertThat(b.docId()).isEqualTo("flame-altar");
        assertThat(b.title()).isEqualTo("灵火祭坛");
        assertThat(b.body()).isEqualTo("祭坛是复活点");
        assertThat(b.url()).isEqualTo("https://w/flame-altar-0");
        assertThat(b.tags()).containsExactly("基础", "据点");
        assertThat(b.source()).isEqualTo(KbBlock.SRC_DOC);
        assertThat(b.retired()).isFalse();
    }

    @Test
    @DisplayName("同一个 id 再写一次 = 覆盖（块数不变）")
    void upsertSameIdOverwrites() {
        store.upsert(block("a", "doc", "标题", "旧正文"), vec(1, 0));
        boolean isNew = store.upsert(block("a", "doc", "标题", "新正文"), vec(0, 1));

        assertThat(isNew).as("已存在 → 不是新增").isFalse();
        assertThat(store.count()).isEqualTo(1);
        assertThat(store.get("a").body()).isEqualTo("新正文");
    }

    @Test
    @DisplayName("不存在的 id → null（而不是报错）")
    void missingIdReturnsNull() {
        assertThat(store.get("不存在")).isNull();
        assertThat(store.get(null)).isNull();
        assertThat(store.get("  ")).isNull();
    }

    @Test
    @DisplayName("id 为空的块拒绝写入（身份是这套设计的地基，不能有空 id）")
    void blankIdIsRejected() {
        assertThatThrownBy(() -> store.upsert(block("", "d", "t", "b"), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("id");
    }

    /* ==================== 向量 ==================== */

    @Test
    @DisplayName("向量按 id 存取，维度可查")
    void vectorRoundTrip() {
        store.upsert(block("a", "d", "t", "b"), vec(0.25f, -1.5f, 3f));

        assertThat(store.vector("a")).containsExactly(0.25f, -1.5f, 3f);
        assertThat(store.dimensions()).isEqualTo(3);
        assertThat(store.countVectors()).isEqualTo(1);
        assertThat(store.vector("没有这个")).isNull();
    }

    @Test
    @DisplayName("★ 只改文字、不传向量时，已有向量不会被抹掉（省一次 embedding 调用）")
    void updatingWithoutVectorKeepsOldVector() {
        store.upsert(block("a", "d", "t", "旧"), vec(1, 2, 3));
        store.upsert(block("a", "d", "t", "只是改了标题"), null);

        assertThat(store.get("a").body()).isEqualTo("只是改了标题");
        assertThat(store.vector("a")).as("向量还在").containsExactly(1, 2, 3);
    }

    @Test
    @DisplayName("批量取向量（检索要整批做余弦）")
    void allVectors() {
        store.upsert(block("a", "d", "t", "b"), vec(1, 0));
        store.upsert(block("b", "d", "t", "b"), vec(0, 1));
        store.upsert(block("c", "d", "t", "b2"), null);   // 还没算向量

        assertThat(store.allVectors()).hasSize(2).containsKeys("a", "b");
        assertThat(store.count()).as("块有 3 个，向量只有 2 个").isEqualTo(3);
        assertThat(store.countVectors()).isEqualTo(2);
    }

    /* ==================== ★ 旧设计做不到的两件事 ==================== */

    @Test
    @DisplayName("★ 真删除：块和它的向量一起没了，且**不会影响别的块**（旧设计只能打墓碑）")
    void deleteReallyDeletes() {
        store.upsert(block("a", "d", "甲", "正文甲"), vec(1, 0));
        store.upsert(block("b", "d", "乙", "正文乙"), vec(0, 1));
        store.upsert(block("c", "d", "丙", "正文丙"), vec(0, 0, 1));

        assertThat(store.delete("b")).isTrue();

        assertThat(store.count()).isEqualTo(2);
        assertThat(store.get("b")).isNull();
        assertThat(store.vector("b")).isNull();
        assertThat(store.get("a").body()).as("邻居一字未动").isEqualTo("正文甲");
        assertThat(store.vector("c")).containsExactly(0f, 0f, 1f);
        assertThat(store.delete("b")).as("再删一次返回 false").isFalse();
    }

    @Test
    @DisplayName("★ 改 id 就是另一个块 —— 行号时代「改一处、后面全部错位」的问题不存在了")
    void changingIdIsJustANewBlock() {
        store.upsert(block("old-id", "d", "标题", "正文"), vec(1, 0));
        store.upsert(block("new-id", "d", "标题", "正文"), vec(1, 0));

        assertThat(store.count()).isEqualTo(2);
        assertThat(store.get("old-id")).isNotNull();
        assertThat(store.get("new-id")).isNotNull();
    }

    /* ==================== 下架 / 分组 / 清空 / 持久化 ==================== */

    @Test
    @DisplayName("下架是标记而不是删除：数据还在，只是不参与检索")
    void retiredIsMarkerNotDeletion() {
        store.upsert(block("a", "d", "甲", "正文"), vec(1, 0));
        store.upsert(block("b", "d", "乙", "正文"), vec(0, 1));

        assertThat(store.setRetired("a", true)).isTrue();

        assertThat(store.all()).hasSize(2);
        assertThat(store.allActive()).extracting(KbBlock::id).containsExactly("b");
        assertThat(store.countRetired()).isEqualTo(1);
        assertThat(store.get("a").body()).as("正文还在，能恢复").isEqualTo("正文");

        store.setRetired("a", false);
        assertThat(store.allActive()).extracting(KbBlock::id).containsExactly("a", "b");
    }

    @Test
    @DisplayName("按文档分组查（整份替换、管理端显示都要用）")
    void queryByDoc() {
        store.upsert(block("docA-0", "docA", "甲", "x"), vec(1, 0));
        store.upsert(block("docA-1", "docA", "甲", "y"), vec(0, 1));
        store.upsert(block("docB-0", "docB", "乙", "z"), vec(1, 1));

        assertThat(store.byDoc("docA")).extracting(KbBlock::id).containsExactly("docA-0", "docA-1");
        assertThat(store.byDoc("docB")).hasSize(1);
        assertThat(store.byDoc("没有")).isEmpty();
    }

    @Test
    @DisplayName("清空全部（首次导入中文语料前用）")
    void clearAll() {
        store.upsert(block("a", "d", "甲", "x"), vec(1, 0));
        store.clearAll();

        assertThat(store.count()).isZero();
        assertThat(store.countVectors()).isZero();
    }

    @Test
    @DisplayName("重启后数据还在（走的是真数据库，不是内存）")
    void survivesReopen() {
        store.upsert(block("a", "d", "标题", "正文"), vec(1, 2, 3));

        KbBlockStore reopened = new KbBlockStore(new KbBlockRepository(new Jdbc(db)));
        reopened.init();

        assertThat(reopened.count()).isEqualTo(1);
        assertThat(reopened.get("a").body()).isEqualTo("正文");
        assertThat(reopened.vector("a")).containsExactly(1, 2, 3);
    }

    /* ==================== ★ 标题向量（C 路） ==================== */

    @Test
    @DisplayName("标题向量按 id 存取，并**记住它是对哪个标题算的**（过期判定全靠它）")
    void titleVectorRoundTrip() {
        store.upsert(block("a", "d", "酸蚀之咬（Acid Bite）", "魔法弹药"), vec(1, 0));
        store.upsertTitleVector("a", "酸蚀之咬（Acid Bite）", vec(0.5f, 0.5f));

        assertThat(store.titleVector("a").vec()).containsExactly(0.5f, 0.5f);
        assertThat(store.titleVector("a").title()).isEqualTo("酸蚀之咬（Acid Bite）");
        assertThat(store.countTitleVectors()).isEqualTo(1);
        assertThat(store.allTitleVectors()).containsKey("a");
        assertThat(store.titleVector("没有这个")).isNull();
    }

    @Test
    @DisplayName("标题向量可以单独重写（正文不变、只换标题向量）")
    void titleVectorCanBeOverwritten() {
        store.upsert(block("a", "d", "旧标题", "正文"), vec(1, 0));
        store.upsertTitleVector("a", "旧标题", vec(1, 0));
        store.upsertTitleVector("a", "新标题", vec(0, 1));

        assertThat(store.countTitleVectors()).as("还是 1 条，不是 2 条").isEqualTo(1);
        assertThat(store.titleVector("a").title()).isEqualTo("新标题");
        assertThat(store.titleVector("a").vec()).containsExactly(0f, 1f);
    }

    @Test
    @DisplayName("★ 删块 / 清空时标题向量一起走（否则新建同 id 的块会捡到旧标题的向量）")
    void titleVectorFollowsBlockLifecycle() {
        store.upsert(block("a", "d", "甲", "正文"), vec(1, 0));
        store.upsertTitleVector("a", "甲", vec(1, 0));

        assertThat(store.delete("a")).isTrue();
        assertThat(store.countTitleVectors()).isZero();

        store.upsert(block("b", "d", "乙", "正文"), vec(1, 0));
        store.upsertTitleVector("b", "乙", vec(1, 0));
        store.clearAll();
        assertThat(store.countTitleVectors()).isZero();
    }
}
