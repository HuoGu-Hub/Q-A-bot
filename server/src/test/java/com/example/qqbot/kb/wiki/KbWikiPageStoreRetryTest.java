package com.example.qqbot.kb.wiki;

import com.example.qqbot.config.PersistenceProperties;
import com.example.qqbot.persistence.Jdbc;
import com.example.qqbot.persistence.KbWikiPageRepository;
import com.example.qqbot.persistence.SqliteDatabase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 文章状态表的两条铁律（2026-10-07 补测，因为实测踩过）。
 *
 * <p>增量导入的判据就是这张表的 {@code revid}：**和远端相等 = 跳过**。
 * 所以「什么时候许写 revid」是一件必须钉死的事：
 *
 * <ul>
 *   <li>只有**成功导完**才许写真实 revid；</li>
 *   <li>记失败**绝对不能**写真实 revid —— 写了这页会被永久跳过，而它一个字都没进语料，
 *       且不报错。实测：2026-10-07 重导时 {@code Lore/Cat Naming Problem} 的标题向量
 *       因代理 GOAWAY 失败，状态行却记下 revid 33016 —— 下次导入再也不会重试它。</li>
 * </ul>
 */
class KbWikiPageStoreRetryTest {

    @TempDir
    Path base;

    private SqliteDatabase db;
    private KbWikiPageStore pages;

    @BeforeEach
    void setUp() {
        PersistenceProperties persist = new PersistenceProperties();
        persist.setDb(base.resolve("qa.sqlite").toString());
        db = new SqliteDatabase(persist);
        db.init();
        pages = new KbWikiPageStore(new KbWikiPageRepository(new Jdbc(db)));
        pages.init();
    }

    @AfterEach
    void close() {
        db.close();
    }

    @Test
    @DisplayName("★ 记失败不动 revid：新页留 0、老页留上次成功的那版（动了就永久跳过）")
    void markErrorNeverAdvancesRevid() {
        // ① 从没有状态行 → 记失败：必须留 0
        pages.markError("Brand New", "Lore", "调用向量模型失败：GOAWAY");

        // ② 以前成功导过（revid=100），这次失败：必须**还是 100**（不是远端的新版本）
        pages.upsert("Already Imported", "Lore", 100, "t", 50, "wiki-already-imported", 1);
        pages.markError("Already Imported", "Lore", "调用向量模型失败：GOAWAY");

        Map<String, Long> known = pages.knownRevisions();
        assertThat(known.get("Brand New"))
                .as("失败的新页必须留 0，否则下次导入认为\"没变\"、永远重试不到")
                .isZero();
        assertThat(known.get("Already Imported"))
                .as("失败的旧页必须保留上次成功的 revid")
                .isEqualTo(100);

        // 失败必须**可见**：状态行里读得出来（管理端 / 导入报告靠它）
        assertThat(pages.pages())
                .filteredOn(p -> "Brand New".equals(p.page()))
                .singleElement()
                .satisfies(p -> {
                    assertThat(p.error()).contains("GOAWAY");
                    assertThat(p.chars()).isZero();
                });
    }

    @Test
    @DisplayName("成功导入才会推进 revid（对照组，免得上面那条被\"永远不写 revid\"糊弄过去）")
    void successAdvancesRevid() {
        pages.upsert("A", "Lore", 33016, "t", 875, "wiki-a", 1);
        assertThat(pages.knownRevisions().get("A")).isEqualTo(33016);
        assertThat(pages.blockCount("A")).isEqualTo(1);
    }
}