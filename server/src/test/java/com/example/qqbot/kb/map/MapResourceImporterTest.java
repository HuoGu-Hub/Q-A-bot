package com.example.qqbot.kb.map;

import com.example.qqbot.kb.MapOptions;
import com.example.qqbot.persistence.MapResourceRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MapResourceImporterTest {

    @TempDir
    Path tmp;

    private MapResourceImporter importer(MapResourceRepository repo, KbMapStore store, Path dir) {
        MapOptions options = new MapOptions();
        options.setModDir(dir.toString());
        options.setModEnabled(true);
        MapResourceImporter importer = new MapResourceImporter(repo, store, new ObjectMapper(), options);
        return importer;
    }

    private static String json() {
        return """
                {
                  "source": {"version": "test-1"},
                  "counts": {"points": 2, "chests": 1, "treeRects": 1},
                  "regions": {"rowStart": [0, 1], "runs": [[1280, 0]], "names": ["Testland"]},
                  "chestTypes": [
                    {"index": 0, "key": "gold", "name": "Gold Chest", "nameZh": "金箱", "count": 1}
                  ],
                  "rows": [
                    {"index": 0, "key": "iron", "name": "Iron", "nameZh": "铁", "kind": "ore", "count": 1},
                    {"index": 90, "key": "lore_note", "name": "Lore Note", "nameZh": "笔记", "kind": "lore", "count": 1},
                    {"index": 69, "key": "springlands_trees", "name": "Springlands Trees", "nameZh": "春境树木", "kind": "tree", "count": 0, "areaColor": "#7fbf5a"}
                  ],
                  "points": [[0, 100, 4, 0, 0], [90, 200, 4, 0, 0]],
                  "chests": [[300, 0, 4, 0]],
                  "trees": [[69, 0, 0, 0, 0]]
                }
                """;
    }

    @Test
    @DisplayName("点位、箱子、图层与 wiki 名字反查都能落进导入结果")
    @SuppressWarnings("unchecked")
    void importsPointsChestsAndNames() throws Exception {
        Path dir = tmp.resolve("1.0.0");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("points.json"), json());

        MapResourceRepository repo = mock(MapResourceRepository.class);
        when(repo.isAvailable()).thenReturn(true);
        when(repo.meta(anyString())).thenReturn(null);
        when(repo.replaceAll(anyString(), anyString(), anyString(), anyString(), anyList(),
                anyList(), anyList(), anyString())).thenReturn(3);

        KbMapStore store = mock(KbMapStore.class);
        when(store.all()).thenReturn(List.of(
                new KbMapStore.Marker("Embervale", "Map:Embervale/Lore", "LoreGeneral", "n1",
                        "The First Note", "", "Lore/The First Note", 202, 4, "")));

        MapResourceImporter.ImportReport report = importer(repo, store, tmp).importNow();

        assertThat(report.imported()).isTrue();
        assertThat(report.points()).isEqualTo(3);
        assertThat(report.areas()).isEqualTo(1);
        assertThat(report.version()).isEqualTo("test-1");

        ArgumentCaptor<List<MapResourceRepository.LayerRow>> layers =
                ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<List<MapResourceRepository.PointRow>> points =
                ArgumentCaptor.forClass(List.class);
        verify(repo).replaceAll(anyString(), anyString(), anyString(), anyString(),
                layers.capture(), points.capture(), anyList(), anyString());

        assertThat(layers.getValue()).extracting(MapResourceRepository.LayerRow::key)
                .containsExactlyInAnyOrder("ore:iron", "lore:lore_note", "chest:gold",
                        "tree:springlands_trees");
        assertThat(layers.getValue()).filteredOn(l -> l.key().equals("chest:gold"))
                .singleElement().extracting(MapResourceRepository.LayerRow::nameZh)
                .isEqualTo("金箱");
        assertThat(points.getValue()).hasSize(3);
        assertThat(points.getValue()).filteredOn(p -> p.layer().equals("lore:lore_note"))
                .singleElement().extracting(MapResourceRepository.PointRow::name)
                .isEqualTo("The First Note");
        assertThat(points.getValue()).allSatisfy(p -> assertThat(p.region()).isEqualTo(0));

        ArgumentCaptor<List<MapResourceRepository.AreaRow>> areas =
                ArgumentCaptor.forClass(List.class);
        verify(repo).replaceAll(anyString(), anyString(), anyString(), anyString(), anyList(),
                anyList(), areas.capture(), anyString());
        assertThat(areas.getValue()).singleElement().satisfies(a -> {
            assertThat(a.layer()).isEqualTo("tree:springlands_trees");
            assertThat(a.x0()).isEqualTo(0);
            assertThat(a.x1()).isEqualTo(32);
            assertThat(a.z0()).isEqualTo(0);
            assertThat(a.z1()).isEqualTo(32);
        });
    }

    @Test
    @DisplayName("同名文件哈希没变时不重复写库")
    void skipsWhenHashMatches() throws Exception {
        Path dir = tmp.resolve("1.0.0");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("points.json"), json());

        MapResourceRepository repo = mock(MapResourceRepository.class);
        when(repo.isAvailable()).thenReturn(true);
        when(repo.meta(anyString())).thenReturn(
                new MapResourceRepository.MetaRow("Embervale", "test-1", "same-hash", "", 3, 0, "t"));

        KbMapStore store = mock(KbMapStore.class);
        when(store.all()).thenReturn(List.of());

        MapResourceImporter importer = importer(repo, store, tmp);
        // 先导入一次，拿到真实哈希
        when(repo.replaceAll(anyString(), anyString(), anyString(), anyString(), anyList(),
                anyList(), anyList(), anyString())).thenReturn(3);
        importer.importNow();
        ArgumentCaptor<String> hash = ArgumentCaptor.forClass(String.class);
        verify(repo).replaceAll(anyString(), anyString(), hash.capture(), anyString(),
                anyList(), anyList(), anyList(), anyString());

        when(repo.meta(anyString())).thenReturn(
                new MapResourceRepository.MetaRow("Embervale", "test-1", hash.getValue(), "", 3, 0,
                        "t"));
        // 图标列是后加的：旧库哈希没变也要重导一次补图标。这里模拟"已经有图标"，
        // 否则 hasIcons() 会把这次导入判成需要重跑。
        when(repo.layers("Embervale")).thenReturn(List.of(
                new MapResourceRepository.LayerRow("chest:iron", "chest", "iron", "铁矿", "铁矿",
                        "#888888", "iron.png", 3, 0)));
        MapResourceImporter.ImportReport second =
                new MapResourceImporter(repo, store, new ObjectMapper(), options(tmp)).ensureLoaded();

        assertThat(second.imported()).isFalse();
        assertThat(second.message()).isEqualTo("已是最新");
    }

    private static MapOptions options(Path dir) {
        MapOptions options = new MapOptions();
        options.setModDir(dir.toString());
        options.setModEnabled(true);
        return options;
    }
}
