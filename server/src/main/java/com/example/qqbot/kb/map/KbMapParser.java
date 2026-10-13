package com.example.qqbot.kb.map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

/**
 * DataMaps（wiki 的 Interactive Data Maps 扩展）JSON 解析器。
 *
 * <h2>为什么先解析再落库，而不是把原始 JSON 原样存起来</h2>
 * marker 是**结构化的事实**（名字 / 描述 / 坐标 / 对应词条）。
 * 存成 blob 就只能整块取出来再在内存里翻，等于把数据库退化成文件柜。
 * 解析后落表，才可能做"按词条 join"、"按区域聚合"、"按名字找坐标"。
 *
 * <h2>四种页面形态（实测，测试夹具一一对应）</h2>
 * <ul>
 *   <li>{@code Map:Embervale/Quests} —— {@code markers} + {@code include}（最有用的一种）；</li>
 *   <li>{@code Map:Blackmire/Main} —— {@code background} + {@code groups} + {@code markers}；</li>
 *   <li>{@code Map:Embervale/Groups} —— 只有 {@code $fragment} + {@code groups}，**没有 markers**；</li>
 *   <li>{@code Map:Embervale} —— 只有 {@code include}，是组合根，**没有 markers**。</li>
 * </ul>
 * 所以解析器必须容忍字段缺失：没有 {@code markers} 就是空列表，不是错误。
 *
 * <h2>页面怎么归属到一个 map</h2>
 * 页面名形如 {@code Map:<map>/<子页>}，取 {@code Map:} 之后的第一段
 * （{@code Map:Embervale/Lore} → {@code Embervale}）。
 *
 * <p><b>不做 include 展开</b>：{@code include} 只是"这页引用了哪些子页"，
 * 展开会让同一批 marker 按不同组合重复入库。同步以"**有 markers 字段的页**"为准，
 * include 只作为元信息记下来。
 */
public final class KbMapParser {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private KbMapParser() {
    }

    /**
     * 地图级元信息（坐标系、底图）。任何一项都可能为 null —— 不是每页都有。
     *
     * <p>⚠️ <b>底图有两套 schema，实测并存</b>：
     * <ul>
     *   <li><b>新</b>（{@code Map:Embervale/Background}）：{@code background: {at, tileSize, tiles:[{image,position}]}}
     *       —— 切成 8×8 张 1280px 瓦片拼成 10240×10240；取 {@link #tileSize()}、{@link #tileCount()}。</li>
     *   <li><b>旧</b>（{@code Map:Blackmire/Main}）：{@code background: "Map-Blackmire.jpg"}
     *       —— 一整张大图；此时 {@link #tileSize()} 为 null，取 {@link #backgroundImage()}，
     *       坐标范围就是图片像素（如 1485×1267）。</li>
     * </ul>
     * 两套都要能读：将来做地图展示时，前者可以直接铺瓦片，后者只能整图缩放。
     */
    public record Meta(String page, String map, String schema, String fragment,
                       double[] topLeft, double[] bottomRight, double[] tileSize, String backdrop,
                       String backgroundImage, int tileCount,
                       double[] tileAt, double zoomMin, double zoomMax, String tilesJson,
                       boolean hasCrs) {

        /**
         * 底图瓦片的排版原点（crs 坐标）。瓦片网格从这里往**右上**铺。
         *
         * <p>实测 {@code Map:Embervale/Background} 的 at 是 (0,0)、crs 是 (0,10240)→(10240,0)，
         * 而 {@code Map:Embervale/Collectibles} 的 at 是 (-3695,-9045) —— 后者并不是同一个原点。
         * 所以 at 必须跟着画布一起存，不能拿 crs 的 topLeft 顶替。
         */
        public double[] tileAtOrDefault() {
            return tileAt != null ? tileAt : topLeft;
        }

        /**
         * 这张画布**能不能被画出来** —— 要有坐标系，还要有底图（瓦片集或整图）。
         *
         * <p>为什么把它做成方法而不是让每个调用方自己判断：判断条件有两处
         * （后端算缩放级别、前端决定是否渲染底图），写两遍迟早会不一致。
         */
        public boolean renderable() {
            return hasCrs && (tileCount > 0 || (backgroundImage != null && !backgroundImage.isBlank()));
        }

        /** 画布的世界宽高（crs 单位）。缺 crs 时返回 {0,0}，由调用方看 {@link #hasCrs()} */
        public double[] extent() {
            if (topLeft == null || bottomRight == null) {
                return new double[]{0, 0};
            }
            return new double[]{Math.abs(bottomRight[0] - topLeft[0]), Math.abs(topLeft[1] - bottomRight[1])};
        }
    }

    /** 一个 marker 分组（图标 / 显示名 / 是否收集品） */
    public record Group(String map, String key, String name, String icon, boolean collectible) {
    }

    /** 一个 marker。{@code article} 是通往知识库的外键 */
    public record Marker(String map, String group, String markerId, String name, String description,
                         String article, double x, double y, String image) {
    }

    /** 一页的解析结果 */
    public record Parsed(Meta meta, List<Group> groups, List<Marker> markers, List<String> includes) {
    }

    /** 解析失败。同步时**按页捕获**：一页坏了不该让整次同步失败 */
    public static class MapParseException extends RuntimeException {
        public MapParseException(String message) {
            super(message);
        }

        public MapParseException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** 从页面名取地图名，见类注释 */
    public static String mapOf(String page) {
        String p = page == null ? "" : page.trim();
        if (p.startsWith("Map:")) {
            p = p.substring(4);
        }
        int slash = p.indexOf('/');
        return slash > 0 ? p.substring(0, slash) : p;
    }

    public static Parsed parse(String page, String json) {
        JsonNode root;
        try {
            root = MAPPER.readTree(json);
        } catch (Exception e) {
            throw new MapParseException("不是合法 JSON：" + page + " —— " + e.getMessage(), e);
        }
        if (root == null || !root.isObject()) {
            throw new MapParseException("顶层不是对象：" + page);
        }
        String map = mapOf(page);
        JsonNode bg = root.path("background");
        // 旧的字符串形态 = 一整张图；新的对象形态 = 瓦片集
        String bgImage = bg.isTextual() ? blankToNull(bg.asText())
                : blankToNull(text(bg, "image"));
        int tileCount = bg.path("tiles").isArray() ? bg.path("tiles").size() : 0;
        // 瓦片清单**原样存 JSON**：排版规则是 wiki 扩展的契约，我们只搬运不改写。
        // 自己重新拼一遍（比如只留 image/position）等于把对方的 schema 复制一份到本地 ——
        // 对方加字段我们就悄悄丢了。
        String tilesJson = bg.path("tiles").isArray() ? json(bg.path("tiles")) : "";
        JsonNode crs = root.path("crs");
        JsonNode zoom = root.path("settings").path("zoom");
        Meta meta = new Meta(page, map,
                text(root, "$schema"), text(root, "$fragment"),
                pair(crs.path("topLeft")),
                pair(crs.path("bottomRight")),
                pair(bg.path("tileSize")),
                blankToNull(text(root.path("settings").path("backdropColor"))),
                bgImage, tileCount,
                // 有些页没有 background.at（整图形态），退回 crs.topLeft —— 见 tileAtOrDefault 的注释
                pair(bg.path("at")),
                num(zoom.path("min"), 0), num(zoom.path("max"), 0),
                tilesJson, crs.isObject());

        List<Group> groups = new ArrayList<>();
        JsonNode gs = root.path("groups");
        if (gs.isObject()) {
            gs.fields().forEachRemaining(e -> {
                JsonNode g = e.getValue();
                groups.add(new Group(map, e.getKey(),
                        blankToNull(text(g, "name")),
                        blankToNull(text(g, "icon")),
                        g.path("isCollectible").asBoolean(false)));
            });
        }

        List<Marker> markers = new ArrayList<>();
        JsonNode ms = root.path("markers");
        if (ms.isObject()) {
            ms.fields().forEachRemaining(e -> {
                String group = e.getKey();
                if (!e.getValue().isArray()) {
                    return;
                }
                for (JsonNode m : e.getValue()) {
                    String id = text(m, "id");
                    // 没有 id 就没法做主键 —— 跳过而不是编一个，宁可少一条也不造一个假身份
                    if (id.isBlank()) {
                        continue;
                    }
                    markers.add(new Marker(map, group, id,
                            text(m, "name"), text(m, "description"), text(m, "article"),
                            m.path("x").asDouble(0), m.path("y").asDouble(0),
                            blankToNull(text(m, "image"))));
                }
            });
        }

        List<String> includes = new ArrayList<>();
        JsonNode inc = root.path("include");
        if (inc.isArray()) {
            inc.forEach(n -> {
                if (n.isTextual() && !n.asText().isBlank()) {
                    includes.add(n.asText());
                }
            });
        }

        return new Parsed(meta, groups, markers, includes);
    }

    private static String text(JsonNode node, String field) {
        return text(node.path(field));
    }

    private static String text(JsonNode node) {
        return node.isTextual() ? node.asText("") : "";
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    /** 数值字段；缺失或不是数字时给默认值 —— 不抛异常，坐标范围只影响显示精度 */
    private static double num(JsonNode node, double dflt) {
        return node.isNumber() ? node.asDouble() : dflt;
    }

    /** 把一段 JSON 原样序列化回去；失败返回空串（画布元信息不该让整页同步失败） */
    private static String json(JsonNode node) {
        try {
            return MAPPER.writeValueAsString(node);
        } catch (Exception e) {
            return "";
        }
    }

    private static double[] pair(JsonNode node) {
        if (!node.isArray() || node.size() != 2) {
            return null;
        }
        return new double[]{node.get(0).asDouble(), node.get(1).asDouble()};
    }
}
