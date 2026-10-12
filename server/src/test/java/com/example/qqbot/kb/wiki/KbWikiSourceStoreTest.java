package com.example.qqbot.kb.wiki;

import com.example.qqbot.config.PersistenceProperties;
import com.example.qqbot.persistence.Jdbc;
import com.example.qqbot.persistence.KbWikiSourceRepository;
import com.example.qqbot.persistence.SqliteDatabase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 拉取来源表 —— 管理端「抓取」页保存的那份范围。
 *
 * <p>钉住两条语义，它们是"页面能取代配置文件"的前提：
 * <ol>
 *   <li><b>表空 = 没配过</b>，调用方要回落到 yml 默认值（老部署不用手动初始化）；</li>
 *   <li><b>保存是整表替换</b> —— 页面上勾选的结果就是完整范围，不留幽灵来源。</li>
 * </ol>
 */
class KbWikiSourceStoreTest {

    @TempDir
    Path base;

    private SqliteDatabase db;
    private KbWikiSourceStore store;

    @BeforeEach
    void setUp() {
        PersistenceProperties persist = new PersistenceProperties();
        persist.setDb(base.resolve("qa.sqlite").toString());
        db = new SqliteDatabase(persist);
        db.init();
        store = new KbWikiSourceStore(new KbWikiSourceRepository(new Jdbc(db)));
        store.init();
    }

    @AfterEach
    void tearDown() {
        if (db != null) {
            db.close();
        }
    }

    @Test
    @DisplayName("★ 表空 = 没配过：enabled 为空，调用方据此回落到 yml 默认值")
    void emptyMeansFallback() {
        assertThat(store.count()).isZero();
        assertThat(store.enabled()).as("空表必须让调用方回落，而不是当成'一个来源都不要'").isEmpty();
    }

    @Test
    @DisplayName("★ 保存后按启用状态过滤；停用的来源不参与导入但页面要能看见")
    void saveAndFilter() {
        store.replace(List.of(
                new KbWikiSourceRepository.Source("Lore", "category", true, ""),
                new KbWikiSourceRepository.Source("Quests/", "prefix", true, ""),
                new KbWikiSourceRepository.Source("Music", "category", false, "")));

        assertThat(store.count()).isEqualTo(3);
        assertThat(store.all()).hasSize(3);
        assertThat(store.enabled()).extracting(KbWikiSourceRepository.Source::name)
                .containsExactlyInAnyOrder("Lore", "Quests/");
    }

    @Test
    @DisplayName("★ 保存是整表替换：页面上勾选的结果就是完整范围")
    void replaceIsWholeTable() {
        store.replace(List.of(new KbWikiSourceRepository.Source("Lore", "category", true, "")));
        store.replace(List.of(new KbWikiSourceRepository.Source("Wildlife", "category", true, "")));

        assertThat(store.count()).isEqualTo(1);
        assertThat(store.enabled()).extracting(KbWikiSourceRepository.Source::name).containsExactly("Wildlife");
    }

    @Test
    @DisplayName("空白名字被丢掉，不会写进表里")
    void blankNamesAreDropped() {
        store.replace(List.of(
                new KbWikiSourceRepository.Source("  ", "category", true, ""),
                new KbWikiSourceRepository.Source("", "prefix", true, "")));
        assertThat(store.count()).isZero();
    }
}
