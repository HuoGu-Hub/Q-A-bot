package com.example.qqbot.kb.map;

import com.example.qqbot.config.PersistenceProperties;
import com.example.qqbot.kb.wiki.WikiApiClient;
import com.example.qqbot.config.KbProperties;
import com.example.qqbot.config.QaProperties;
import com.example.qqbot.persistence.Jdbc;
import com.example.qqbot.persistence.KbMapRepository;
import com.example.qqbot.persistence.QaStoreRepository;
import com.example.qqbot.persistence.SqliteDatabase;
import com.example.qqbot.qa.QaStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 地图同步的**端到端**验收：真的去 wiki 拉一次，真的落库，然后查回来。
 *
 * <p>默认**跳过**（{@code -Dqqbot.map.sync=on} 才跑）：首次同步要拉 32 页、约 548 KB、
 * 写一千多行 —— 这是"验证一次"的量级，不该塞进每次构建。
 *
 * <pre>
 * mvn '-Dqqbot.map.sync=on' '-Dtest=KbMapSyncTest' test
 * </pre>
 *
 * <p>它跑在**临时库**上，绝不碰生产库。
 */
class KbMapSyncTest {

    @TempDir
    Path tmp;

    private QaStore qaStore;
    private SqliteDatabase sqlite;

    @AfterEach
    void tearDown() {
        if (qaStore != null) {
            sqlite.close();
        }
    }

    /** 清理器在同步末尾会被调一次；这里只关心它别炸，所以图片缓存与语料都用假件 */
    private static MapResidueCleaner cleanerFor(com.example.qqbot.kb.KbPolicy policy, KbMapStore store) {
        MapImageCache images = mock(MapImageCache.class);
        when(images.purgeMapsNotIn(any())).thenReturn(new MapImageCache.CachePurge(0, 0, List.of()));
        return new MapResidueCleaner(policy, store, images, mock(LocationCorpusBuilder.class));
    }

    @Test
    @DisplayName("★ 真拉一次 wiki 地图数据：只拉白名单里的世界，千余 marker 且 article 能 join 回来")
    void syncFromRealWiki() {
        Assumptions.assumeTrue("on".equalsIgnoreCase(System.getProperty("qqbot.map.sync")),
                "需要 -Dqqbot.map.sync=on 才跑（会真的访问 wiki 并写临时库）");

        ObjectMapper mapper = new ObjectMapper();
        QaProperties qa = new QaProperties();
        PersistenceProperties persist = new PersistenceProperties();
        persist.setDb(tmp.resolve("map.sqlite").toString());
        sqlite = new SqliteDatabase(persist);
        sqlite.init();
        qaStore = new QaStore(new QaStoreRepository(new Jdbc(sqlite)), qa, mapper);
        qaStore.init();

        KbMapStore store = new KbMapStore(new KbMapRepository(new Jdbc(sqlite)));
        store.init();
        assertThat(store.isAvailable()).isTrue();

        KbProperties kb = new KbProperties();
        WikiApiClient client = new WikiApiClient(kb, mapper);
        // ⚠️ 用默认白名单（app.kb.map-sync.maps）：命名空间里除了当前世界，
        //    还躺着抢先体验版的旧世界图和几个区域小图 —— 那些不该进库
        KbMapSyncService service = new KbMapSyncService(client, store, kb,
                cleanerFor(kb, store));

        // 先 dryRun：只比版本，不取正文
        KbMapSyncService.SyncReport dry = service.sync(true);
        System.out.printf("dryRun：白名单内 %d 页，跳过 %d 页，其中 %d 页需要同步%n",
                dry.pages(), dry.skipped(), dry.changed());
        assertThat(dry.pages()).as("白名单里只有 Embervale，页数应当只是它那几页")
                .isBetween(5, 20);
        assertThat(dry.skipped()).as("命名空间里其余的页属于旧世界与区域小图")
                .isGreaterThan(5);
        assertThat(dry.changed()).isEqualTo(dry.pages());

        // 真同步
        KbMapSyncService.SyncReport r = service.sync(false);
        System.out.printf("同步：解析 %d 页 / 写入 %d 个 marker / 失败 %d 页 / %d ms%n",
                r.parsed(), r.markers(), r.failed(), r.elapsedMs());
        r.byGroup().entrySet().stream().limit(12)
                .forEach(e -> System.out.printf("   %-34s %d%n", e.getKey(), e.getValue()));

        assertThat(r.failed()).as("失败页：" + r.errors()).isZero();
        assertThat(r.parsed()).isGreaterThan(5);
        assertThat(store.markerCount()).as("Embervale 一张图就约 1,064 个 marker").isGreaterThan(800);
        assertThat(store.groupCount()).isGreaterThan(30);
        // ★ 白名单必须真的把别的地图挡在库外：这是"多地图残留"的源头开关
        assertThat(store.mapNames()).containsExactly("Embervale");

        // 幂等 + 增量：立刻再同步一次，应当 0 页需要处理
        KbMapSyncService.SyncReport again = service.sync(false);
        assertThat(again.changed()).as("revid 没变，第二次同步不该再做任何事").isZero();
        assertThat(again.markers()).isZero();

        // article 是通往知识库的外键 —— 必须能按它查回来
        List<KbMapStore.Marker> byArticle = store.byArticle("Ancient Spire", 20);
        assertThat(byArticle).as("article=Ancient Spire 的 marker").isNotEmpty();
        // 白名单生效后库里只剩一张地图，所以每条都应当来自它。
        // （以前这里只能断言"包含 Embervale" —— 因为同一个 article 会在
        //   Embervale 与 Blackmire 各有一份，正是多地图混进来的证据。）
        assertThat(byArticle).extracting(KbMapStore.Marker::map).containsOnly("Embervale");
        assertThat(byArticle).allSatisfy(m -> assertThat(m.x()).isGreaterThan(0));
        // article 之外，description 里还带 wiki 内链（如 [[Fell Thunderbrute]]）——
        // 这是 NPC / 怪物名字的来源，也是下一步语料扩维要挖的东西
        assertThat(byArticle).anySatisfy(m -> assertThat(m.description()).isNotBlank());

        // 名字模糊查（玩家只记得大概名字时的入口）
        assertThat(store.byNameLike("Sanctum", 50)).isNotEmpty();
        System.out.println("地图列表：" + store.maps());
    }
}
