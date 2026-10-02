package com.example.qqbot.site;

import com.example.qqbot.config.QaProperties;
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
 * 轮播清单（SQLite 那一层）的验收测试。
 *
 * <p>重点在**顺序**：上移/下移、删中间一张之后不留空档 —— 这是列表页最容易出错的地方。
 */
class CarouselStoreTest {

    @TempDir
    Path base;

    private QaStore qaStore;

    private CarouselStore store;

    @BeforeEach
    void setUp() {
        QaProperties qa = new QaProperties();
        qa.setDb(base.resolve("qa.sqlite").toString());
        qaStore = new QaStore(qa, new ObjectMapper());
        qaStore.init();

        store = new CarouselStore(qaStore);
        store.init();
    }

    private long add(String name) {
        return store.add(name + ".png", "image/png", 10, 20, "", name);
    }

    @AfterEach
    void closeStores() {
        qaStore.close();
    }

    @Test
    @DisplayName("新增按顺序排在最后；list 按 sort 升序")
    void addsInOrder() {
        long a = add("A");
        long b = add("B");
        long c = add("C");

        assertThat(a).isPositive();
        assertThat(store.list()).extracting(CarouselStore.Item::caption)
                .containsExactly("A", "B", "C");
        assertThat(store.count()).isEqualTo(3);
    }

    @Test
    @DisplayName("★ 上移 / 下移：换位置并把 sort 重排成 0..n-1")
    void movesAndResequences() {
        add("A");
        add("B");
        add("C");

        assertThat(store.move(store.list().get(0).id(), 1)).as("A 下移").isTrue();
        assertThat(store.list()).extracting(CarouselStore.Item::caption)
                .containsExactly("B", "A", "C");
        assertThat(store.list()).extracting(CarouselStore.Item::sort).containsExactly(0, 1, 2);

        assertThat(store.move(store.list().get(2).id(), 1)).as("最后一张再下移 → 不动").isFalse();
        assertThat(store.move(store.list().get(0).id(), -1)).as("第一张再上移 → 不动").isFalse();
        assertThat(store.move(store.list().get(0).id(), 0)).as("delta=0 不算动").isFalse();
    }

    @Test
    @DisplayName("删中间一张之后，剩下的 sort 仍然是 0..n-1（不留空档）")
    void deleteResequences() {
        add("A");
        add("B");
        add("C");

        CarouselStore.Item gone = store.delete(store.list().get(1).id());

        assertThat(gone).isNotNull();
        assertThat(gone.caption()).isEqualTo("B");
        assertThat(store.list()).extracting(CarouselStore.Item::caption).containsExactly("A", "C");
        assertThat(store.list()).extracting(CarouselStore.Item::sort).containsExactly(0, 1);
        assertThat(store.delete(9999L)).as("删不存在的返回 null").isNull();
    }

    @Test
    @DisplayName("改说明 / 链接 / 启停；传 null 的字段不动")
    void updatesPartially() {
        long id = add("A");

        CarouselStore.Item a = store.update(id, "https://example.com", "改过的说明", false);
        assertThat(a).isNotNull();
        assertThat(a.link()).isEqualTo("https://example.com");
        assertThat(a.caption()).isEqualTo("改过的说明");
        assertThat(a.enabled()).isFalse();

        // 只改 enabled，link/caption 必须原样保留
        CarouselStore.Item b = store.update(id, null, null, true);
        assertThat(b.enabled()).isTrue();
        assertThat(b.link()).isEqualTo("https://example.com");
        assertThat(b.caption()).isEqualTo("改过的说明");

        assertThat(store.update(9999L, null, null, true)).isNull();
    }

    @Test
    @DisplayName("listEnabled 只给启用的（公开站用）")
    void listEnabledFilters() {
        long a = add("A");
        add("B");
        store.update(a, null, null, false);

        assertThat(store.list()).hasSize(2);
        assertThat(store.listEnabled()).extracting(CarouselStore.Item::caption).containsExactly("B");
    }

    @Test
    @DisplayName("宽高等元数据原样存回来")
    void keepsMetadata() {
        long id = store.add("x.webp", "image/webp", 0, 0, "https://a.b", "说明");

        CarouselStore.Item it = store.get(id);
        assertThat(it.mime()).isEqualTo("image/webp");
        assertThat(it.width()).isZero();
        assertThat(it.height()).isZero();
        assertThat(it.createdAt()).isNotBlank();
        assertThat(List.of(it.link())).containsExactly("https://a.b");
    }
}
