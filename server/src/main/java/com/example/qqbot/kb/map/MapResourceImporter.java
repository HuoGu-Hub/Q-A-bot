package com.example.qqbot.kb.map;

import com.example.qqbot.kb.MapOptions;
import com.example.qqbot.persistence.MapResourceRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Stream;

/**
 * 把 The Worst Map Mod 导出的 points.json 灌进库。
 *
 * <p>这个类只在**第一次有人要看资源图层**时跑，不在启动时跑：
 * 导入 36k 行要一两秒，而绝大多数请求根本不看资源层。导入是幂等的 ——
 * 文件哈希没变就直接复用库里的快照，变了才整批替换。
 *
 * <p>点位与游戏版本解耦：这是一份快照，不是实时数据。游戏更新后旧的照样能用，
 * 只是不含新增内容。真正需要重导的信号只有两个：文件换了，或者 wiki 画布换了。
 */
@Component
public class MapResourceImporter {

    private static final Logger log = LoggerFactory.getLogger(MapResourceImporter.class);

    /** wiki 里这些分组的点可以用作收集品的名字来源 */
    private static final Set<String> COLLECTIBLE_GROUPS = Set.of(
            "Fossil", "Music_Sheet", "DrakRelief", "FlameMosaic", "CyclopsSkeleton",
            "DragonsMapPiece", "QueenPikemeadStatuePiece", "WallTapestry",
            "AlchemistWallHanging");

    /** 这些类型没有逐点名字，只能用“类型 · 区域 #序号”区分 */
    private static final Set<String> GENERIC_NAMED = Set.of(
            "boss", "unique_weapon", "unique_equipment");

    public record ImportReport(boolean imported, boolean found, String version, int layers,
                               int points, int areas, String message) {
    }

    private record LayerDef(String key, String kind, String subtype, String name, int sort,
                            String color, String icon, int index) {
    }

    private final MapResourceRepository repo;
    private final KbMapStore mapStore;
    private final ObjectMapper mapper;
    private final MapOptions options;

    private final Object lock = new Object();
    private volatile ImportReport last;

    public MapResourceImporter(MapResourceRepository repo, KbMapStore mapStore,
                               ObjectMapper mapper, MapOptions options) {
        this.repo = repo;
        this.mapStore = mapStore;
        this.mapper = mapper;
        this.options = options;
    }

    /** 第一次调用时导入；之后直接返回上次结果。文件没变时是一次 COUNT 级开销 */
    public ImportReport ensureLoaded() {
        ImportReport done = last;
        if (done != null && done.imported()) {
            return done;
        }
        synchronized (lock) {
            if (last != null && last.imported()) {
                return last;
            }
            last = run(false);
            return last;
        }
    }

    /** 强制重新导入 —— 管理端按钮或运维命令用 */
    public ImportReport importNow() {
        synchronized (lock) {
            last = run(true);
            return last;
        }
    }

    private ImportReport run(boolean force) {
        if (!options.isModEnabled()) {
            return new ImportReport(false, false, "", 0, 0, 0, "资源点位已关闭");
        }
        if (!repo.isAvailable()) {
            return new ImportReport(false, false, "", 0, 0, 0, "问答库不可用");
        }
        Path file;
        try {
            file = resolveFile();
        } catch (Exception e) {
            log.warn("[MAP-RES] 找点位文件失败：{}", e.getMessage());
            return new ImportReport(false, false, "", 0, 0, 0, "找不到点位文件");
        }
        if (file == null) {
            return new ImportReport(false, false, "", 0, 0, 0, "没有 points.json");
        }

        try {
            String hash = sha256(file);
            MapResourceRepository.MetaRow meta = repo.meta("Embervale");
            if (!force && meta != null && hash.equals(meta.sourceHash()) && meta.points() > 0
                    && !repo.regionNamesMissing("Embervale") && hasIcons()) {
                return new ImportReport(false, true, meta.version(), 0, meta.points(), meta.areas(), "已是最新");
            }
            ImportReport report = parseAndStore(file, hash);
            log.info("[MAP-RES] 导入完成：{} 点 / {} 块林地 / {} 层",
                    report.points(), report.areas(), report.layers());
            return report;
        } catch (Exception e) {
            log.warn("[MAP-RES] 导入失败：{}", e.getMessage(), e);
            return new ImportReport(false, true, "", 0, 0, 0, "导入失败：" + e.getMessage());
        }
    }

    /**
     * 点位目录既可以是单个 json，也可以是按版本分的目录。
     * 目录形态取版本号最大的那份，不按修改时间 —— 拷贝文件会把 mtime 弄乱。
     */
    private Path resolveFile() throws IOException {
        String dir = options.getModDir();
        if (dir == null || dir.isBlank()) {
            return null;
        }
        Path root = Path.of(dir);
        if (Files.isRegularFile(root)) {
            return root;
        }
        Path found = null;
        if (Files.isDirectory(root)) {
            Path direct = root.resolve("points.json");
            if (Files.isRegularFile(direct)) {
                found = direct;
            } else {
                try (Stream<Path> dirs = Files.list(root)) {
                    found = dirs.filter(Files::isDirectory)
                            .map(d -> d.resolve("points.json"))
                            .filter(Files::isRegularFile)
                            .max(Comparator.comparing(
                                    p -> versionOf(p), MapResourceImporter::compareVersion))
                            .orElse(null);
                }
            }
        }
        return found != null ? found : extractBundled();
    }

    /** 磁盘上没有点位文件时，把随 jar 发布的那份释放到数据目录，后续走同一条路径 */
    private Path extractBundled() {
        String name = options.getModResource();
        if (name == null || name.isBlank()) {
            return null;
        }
        try {
            Resource resource = new PathMatchingResourcePatternResolver()
                    .getResource(name.startsWith("classpath:") ? name : "classpath:" + name);
            if (!resource.exists()) {
                return null;
            }
            Path target = Path.of(options.getModDir(), "bundled", "points.json");
            Files.createDirectories(target.getParent());
            try (InputStream in = resource.getInputStream()) {
                Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
            }
            log.info("[MAP-RES] 从 classpath 释放点位文件到 {}", target);
            return target;
        } catch (Exception e) {
            log.warn("[MAP-RES] 释放 classpath 点位失败：{}", e.getMessage());
            return null;
        }
    }

    private static String versionOf(Path points) {
        Path parent = points.getParent();
        return parent == null ? "" : parent.getFileName().toString();
    }

    private static int compareVersion(String a, String b) {
        String[] x = a.split("\\.");
        String[] y = b.split("\\.");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            int xi = i < x.length ? number(x[i]) : 0;
            int yi = i < y.length ? number(y[i]) : 0;
            if (xi != yi) {
                return Integer.compare(xi, yi);
            }
        }
        return a.compareTo(b);
    }

    private static int number(String s) {
        try {
            return Integer.parseInt(s.replaceAll("[^0-9]", ""));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private ImportReport parseAndStore(Path file, String hash) throws IOException {
        JsonNode root = mapper.readTree(Files.readAllBytes(file));
        String version = root.path("source").path("version").asText("unknown");
        JsonNode rows = root.path("rows");
        JsonNode points = root.path("points");
        JsonNode chestTypes = root.path("chestTypes");
        JsonNode chests = root.path("chests");
        JsonNode trees = root.path("trees");
        Map<Integer, Integer> treeCounts = new HashMap<>();
        for (JsonNode tree : trees) {
            treeCounts.merge(tree.path(0).asInt(), 1, Integer::sum);
        }
        int expectedPoints = root.path("counts").path("points").asInt(0);
        int expectedChests = root.path("counts").path("chests").asInt(0);
        int expectedTrees = root.path("counts").path("treeRects").asInt(0);
        if (expectedPoints > 0 && points.size() != expectedPoints) {
            throw new IllegalStateException("点位数量对不上：声明 " + expectedPoints
                    + "，实际 " + points.size());
        }
        if (expectedChests > 0 && chests.size() != expectedChests) {
            throw new IllegalStateException("箱子数量对不上：声明 " + expectedChests
                    + "，实际 " + chests.size());
        }
        if (expectedTrees > 0 && trees.size() != expectedTrees) {
            throw new IllegalStateException("林地数量对不上：声明 " + expectedTrees
                    + "，实际 " + trees.size());
        }

        RegionGrid regions = RegionGrid.of(root.path("regions"));
        WikiIndex wiki = new WikiIndex(mapStore.all());
        Map<String, String> icons = loadIcons();

        Map<Integer, LayerDef> byRow = new HashMap<>();
        Map<Integer, LayerDef> byTreeRow = new HashMap<>();
        List<LayerDef> layers = new ArrayList<>();
        for (JsonNode row : rows) {
            int index = row.path("index").asInt();
            String kind = row.path("kind").asText("world");
            int count = "tree".equals(kind)
                    ? treeCounts.getOrDefault(index, 0)
                    : row.path("count").asInt();
            if (count <= 0) {
                continue;
            }
            String subtype = row.path("key").asText();
            String name = row.path("nameZh").asText("");
            if (name.isBlank()) {
                name = row.path("name").asText();
            }
            String color = row.path("areaColor").asText("");
            if (color.isBlank()) {
                color = ResourceColors.of(kind, subtype, index);
            }
            String key = kind + ":" + subtype;
            LayerDef def = new LayerDef(key, kind, subtype, name, index, color,
                    icons.getOrDefault(key, ""), index);
            if ("tree".equals(kind)) {
                byTreeRow.put(index, def);
            } else {
                byRow.put(index, def);
            }
            layers.add(def);
        }

        Map<Integer, LayerDef> chestByType = new HashMap<>();
        for (JsonNode type : chestTypes) {
            int index = type.path("index").asInt();
            int count = type.path("count").asInt();
            if (count <= 0) {
                continue;
            }
            String subtype = type.path("key").asText();
            String name = type.path("nameZh").asText("");
            if (name.isBlank()) {
                name = type.path("name").asText();
            }
            String key = "chest:" + subtype;
            LayerDef def = new LayerDef(key, "chest", subtype, name, 1000 + index,
                    ResourceColors.of("chest", subtype, index), icons.getOrDefault(key, ""), index);
            chestByType.put(index, def);
            layers.add(def);
        }

        Map<String, Integer> counts = new LinkedHashMap<>();
        List<MapResourceRepository.PointRow> out = new ArrayList<>(points.size() + chests.size());
        Map<String, Integer> namedSeq = new HashMap<>();
        int seq = 0;

        for (JsonNode point : points) {
            int rowIndex = point.path(0).asInt();
            LayerDef def = byRow.get(rowIndex);
            if (def == null) {
                continue;
            }
            double x = point.path(1).asDouble();
            double z = point.path(2).asDouble();
            int region = regions.at(x, z);
            String regionName = region >= 0 ? regions.name(region) : "";
            String name = nameFor(def, x, z, region, regions.name(region), wiki, namedSeq);
            out.add(new MapResourceRepository.PointRow(def.key(), name, region, regionName, x, z,
                    "p:" + seq++));
            counts.merge(def.key(), 1, Integer::sum);
        }
        int chestSeq = 0;
        for (JsonNode chest : chests) {
            int sub = chest.path(3).asInt();
            LayerDef def = chestByType.get(sub);
            if (def == null) {
                continue;
            }
            double x = chest.path(0).asDouble();
            double z = chest.path(2).asDouble();
            int region = regions.at(x, z);
            String regionName = region >= 0 ? regions.name(region) : "";
            out.add(new MapResourceRepository.PointRow(def.key(), def.name(), region, regionName, x, z,
                    "c:" + chestSeq++));
            counts.merge(def.key(), 1, Integer::sum);
        }

        List<MapResourceRepository.AreaRow> areaRows = new ArrayList<>();
        int treeSeq = 0;
        for (JsonNode tree : trees) {
            LayerDef def = byTreeRow.get(tree.path(0).asInt());
            if (def == null) {
                continue;
            }
            double x0 = clamp(tree.path(3).asInt() * 32.0);
            double x1 = clamp((tree.path(4).asInt() + 1) * 32.0);
            double z0 = clamp(tree.path(1).asInt() * 32.0);
            double z1 = clamp((tree.path(2).asInt() + 1) * 32.0);
            if (x1 <= x0 || z1 <= z0) {
                continue;
            }
            int region = regions.at((x0 + x1) / 2, (z0 + z1) / 2);
            areaRows.add(new MapResourceRepository.AreaRow(def.key(), def.name(), region,
                    x0, z0, x1, z1, "t:" + treeSeq++));
            counts.merge(def.key(), 1, Integer::sum);
        }

        List<MapResourceRepository.LayerRow> layerRows = new ArrayList<>();
        for (LayerDef def : layers) {
            int n = counts.getOrDefault(def.key(), 0);
            if (n == 0) {
                continue;
            }
            layerRows.add(new MapResourceRepository.LayerRow(def.key(), def.kind(), def.subtype(),
                    def.name(), def.name(), def.color(), def.icon(), n, def.sort()));
        }
        int stored = repo.replaceAll("Embervale", version, hash, canvasFingerprint(),
                layerRows, out, areaRows, Instant.now().toString());
        return new ImportReport(true, true, version, layerRows.size(), stored, areaRows.size(),
                version + " / " + stored + " 点 / " + areaRows.size() + " 块林地");
    }

    /**
     * 当前 wiki 画布的几何指纹。资源点存的是世界坐标，画布换了它就会整体偏移；
     * 把导入时的指纹留下来，后台一眼能看出"该复核了"。
     */
    private String canvasFingerprint() {
        List<KbMapStore.Canvas> all = mapStore.canvases();
        if (all == null || all.isEmpty()) {
            return "";
        }
        String page = WikiPlaceDirectory.primaryCanvasOf(all).get("Embervale");
        if (page == null) {
            return "";
        }
        for (KbMapStore.Canvas c : all) {
            if ("Embervale".equals(c.map()) && page.equals(c.page())) {
                return CanvasFingerprint.of(c);
            }
        }
        return "";
    }

    /** 旧库还没有 icon 列时，文件哈希没变也要重导一次，把图标补上 */
    private boolean hasIcons() {
        if (loadIcons().isEmpty()) {
            return true;
        }
        try {
            var layers = repo.layers("Embervale");
            if (layers == null) {
                return true;
            }
            return layers.stream().anyMatch(l -> !l.icon().isBlank());
        } catch (Exception e) {
            return false;
        }
    }

    /** 图标映射：key 是 layer key，value 是 classpath:map-icons 下的文件名 */
    private Map<String, String> loadIcons() {
        Map<String, String> out = new HashMap<>();
        try {
            Resource resource = new PathMatchingResourcePatternResolver()
                    .getResource("classpath:map-mod/icons.json");
            if (!resource.exists()) {
                return out;
            }
            try (InputStream in = resource.getInputStream()) {
                JsonNode node = mapper.readTree(in);
                node.fields().forEachRemaining(e -> out.put(e.getKey(), e.getValue().asText()));
            }
        } catch (Exception e) {
            log.warn("[MAP-RES] 读图标映射失败：{}", e.getMessage());
        }
        return out;
    }

    private static double clamp(double v) {
        return Math.max(0, Math.min(10240, v));
    }

    private static String nameFor(LayerDef def, double x, double z, int region, String regionName,
                                  WikiIndex wiki, Map<String, Integer> seq) {
        if ("lore".equals(def.kind())) {
            KbMapStore.Marker m = wiki.nearest(x, z, g -> g.startsWith("Lore"), 10);
            if (m != null && !m.name().isBlank()) {
                return m.name();
            }
        }
        if ("collectible".equals(def.kind())) {
            KbMapStore.Marker m = wiki.nearest(x, z, COLLECTIBLE_GROUPS::contains, 10);
            if (m != null && !m.name().isBlank()) {
                return m.name();
            }
        }
        if ("lore".equals(def.kind()) || "collectible".equals(def.kind())
                || GENERIC_NAMED.contains(def.kind())) {
            int n = seq.merge(def.key(), 1, Integer::sum);
            return def.name() + " · " + regionName + " #" + n;
        }
        return def.name();
    }

    private static String sha256(Path file) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                md.update(buf, 0, n);
            }
        }
        return HexFormat.of().formatHex(md.digest());
    }

    /* ==================== 区域网格 ==================== */

    private record RegionGrid(int[] rowStart, int[][] runs, String[] names) {

        static RegionGrid of(JsonNode node) {
            int[] rowStart = ints(node.path("rowStart"));
            String[] names = strings(node.path("names"));
            JsonNode runsNode = node.path("runs");
            int[][] runs = new int[runsNode.size()][];
            for (int i = 0; i < runsNode.size(); i++) {
                runs[i] = ints(runsNode.get(i));
            }
            return new RegionGrid(rowStart, runs, names);
        }

        int at(double x, double z) {
            if (rowStart.length < 2) {
                return -1;
            }
            int gx = (int) Math.floor(x / 8);
            int gz = (int) Math.floor(z / 8);
            if (gx < 0 || gz < 0 || gz >= rowStart.length - 1) {
                return -1;
            }
            int from = rowStart[gz];
            int to = Math.min(rowStart[gz + 1], runs.length);
            int cell = 0;
            for (int i = from; i < to; i++) {
                if (runs[i].length < 2) {
                    continue;
                }
                if (gx < cell + runs[i][0]) {
                    return runs[i][1];
                }
                cell += runs[i][0];
            }
            return -1;
        }

        String name(int region) {
            if (region < 0 || region >= names.length) {
                return "未知区域";
            }
            return names[region];
        }

        private static int[] ints(JsonNode node) {
            int[] out = new int[node.size()];
            for (int i = 0; i < out.length; i++) {
                out[i] = node.get(i).asInt();
            }
            return out;
        }

        private static String[] strings(JsonNode node) {
            String[] out = new String[node.size()];
            for (int i = 0; i < out.length; i++) {
                out[i] = node.get(i).asText();
            }
            return out;
        }
    }

    /* ==================== wiki 名字反查 ==================== */

    private static final class WikiIndex {

        private final Map<Long, List<KbMapStore.Marker>> grid = new HashMap<>();

        WikiIndex(List<KbMapStore.Marker> markers) {
            for (KbMapStore.Marker m : markers) {
                if ("Embervale".equals(m.map())) {
                    grid.computeIfAbsent(key(m.x(), m.y()), k -> new ArrayList<>()).add(m);
                }
            }
        }

        KbMapStore.Marker nearest(double x, double z, Predicate<String> group, double maxMeters) {
            long gx = (long) Math.floor(x / 64);
            long gz = (long) Math.floor(z / 64);
            KbMapStore.Marker best = null;
            double bestD = maxMeters * maxMeters;
            for (long dx = -1; dx <= 1; dx++) {
                for (long dz = -1; dz <= 1; dz++) {
                    List<KbMapStore.Marker> bucket = grid.get((gx + dx) << 32 ^ (gz + dz));
                    if (bucket == null) {
                        continue;
                    }
                    for (KbMapStore.Marker m : bucket) {
                        if (!group.test(m.group())) {
                            continue;
                        }
                        double d = (m.x() - x) * (m.x() - x) + (m.y() - z) * (m.y() - z);
                        if (d < bestD) {
                            bestD = d;
                            best = m;
                        }
                    }
                }
            }
            return best;
        }

        private static long key(double x, double z) {
            return ((long) Math.floor(x / 64) << 32) ^ (long) Math.floor(z / 64);
        }
    }
}
