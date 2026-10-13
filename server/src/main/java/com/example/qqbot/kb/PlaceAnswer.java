package com.example.qqbot.kb;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 「哪里有 X」的**确定性回答** —— 由点位数据算出来，而不是让大模型猜。
 *
 * <h2>为什么要有这一层，而不是把点位直接喂给模型</h2>
 * 模型拿到一串坐标后做的第一件事是"自己编一个相对位置"，而它没法可靠地
 * 比较 1700 个点的距离 —— 结果是编得像模像样的错误方位。
 * 这里的职责就是把"算"做完，模型只负责把算好的结论组织成人话。
 *
 * <h2>输出的确定性</h2>
 * 同样的库、同样的提问，{@link #text()} 一定一样：没有任何随机、没有模型参与。
 * 这既是它能被测死的前提，也是群里复盘"为什么这么答"的依据。
 */
public final class PlaceAnswer {

    /** 一次回答里最多列几处 —— 手机上再多也读不完 */
    public static final int DEFAULT_LIMIT = 5;

    /**
     * 一条命中。
     *
     * @param neighbour 相对位置；库里的点太少时可能算不出来（{@code null}）
     */
    public record Hit(PlacePoint place, RelativeLocation.Neighbour neighbour) {

        /** 这一条的一行描述 */
        public String line(double unitsPerMeter) {
            StringBuilder sb = new StringBuilder("· ");
            if (neighbour != null) {
                sb.append(neighbour.phrase(unitsPerMeter)).append('。');
            }
            sb.append("坐标 (")
                    .append(Math.round(place.x())).append(", ")
                    .append(Math.round(place.y())).append(')');
            if (place.note() != null && !place.note().isBlank()) {
                sb.append("。备注：").append(place.note().strip());
            }
            return sb.toString();
        }
    }

    /**
     * 一次问答的结果。
     *
     * @param term 从提问里取出的地点词
     * @param hits 命中的点位，已按"是不是同名"与距离排序
     * @param note 找不到时的说明（找到了为空）
     */
    public record Answer(String term, List<Hit> hits, String note) {

        public boolean found() {
            return !hits.isEmpty();
        }

        /** 给大模型 / 公开站读的完整文本 */
        public String text() {
            return text(0);
        }

        /**
         * @param unitsPerMeter 1 米等于多少坐标单位；{@code <= 0} 表示不输出米（默认）
         */
        public String text(double unitsPerMeter) {
            if (!found()) {
                return note == null || note.isBlank()
                        ? "没有查到「" + term + "」的位置。"
                        : note;
            }
            Set<String> maps = new LinkedHashSet<>();
            for (Hit h : hits) {
                maps.add(h.place().map());
            }
            StringBuilder sb = new StringBuilder();
            sb.append("「").append(term).append("」在地图上有 ").append(hits.size()).append(" 处");
            if (maps.size() == 1) {
                sb.append("（地图：").append(maps.iterator().next()).append("）");
            }
            sb.append("：\n");
            for (Hit h : hits) {
                sb.append(h.line(unitsPerMeter)).append('\n');
            }
            return sb.toString().strip();
        }

        /** 第一条命中的描述，界面上的"主答案" */
        public String primaryLine() {
            return found() ? hits.get(0).line(0) : (note == null ? "" : note);
        }
    }

    private PlaceAnswer() {
    }

    public static Answer answer(String term, PlaceDirectory directory, int limit) {
        return answer(term, directory, limit, 0);
    }

    /**
     * 组装一次回答。
     *
     * <p>每一步的取舍：
     * <ol>
     *   <li>目录不可用 → 明确说"地图数据不可用"，而不是"没查到" ——
     *       这两种情况让运维看到的排查方向完全不同；</li>
     *   <li>没有命中 → 给出"可以请管理员补充"的说明，因为点位是可以手工加的，
     *       这比干巴巴的"不知道"对群友更有用；</li>
     *   <li>锚点从**全量点位**里挑，不是从命中的那几条里挑 ——
     *       参照物本来就该是别的地点。</li>
     * </ol>
     */
    public static Answer answer(String term, PlaceDirectory directory, int limit, double unitsPerMeter) {
        String t = term == null ? "" : term.strip();
        if (t.isEmpty()) {
            return new Answer("", List.of(), "没有给出要查的地点。");
        }
        if (directory == null || !directory.isAvailable()) {
            return new Answer(t, List.of(), "地图数据暂时不可用，查不到「" + t + "」的位置。");
        }
        int want = limit <= 0 ? DEFAULT_LIMIT : limit;
        List<PlacePoint> found = directory.find(t, Math.max(want * 4, 12));
        if (found.isEmpty()) {
            return new Answer(t, List.of(), "地图上没有标记过「" + t + "」。"
                    + "如果确实有这个地方，可以让管理员在地图页上补一个点位。");
        }

        List<PlacePoint> universe = directory.all();
        // 地图尺寸查一次就够 —— 放在循环里会让每条命中都重扫一遍全部画布
        java.util.Map<String, double[]> sizes = directory.worldSizes();
        List<Hit> hits = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (PlacePoint p : found) {
            String key = p.name() + "@" + Math.round(p.x()) + "," + Math.round(p.y());
            if (!seen.add(key)) {
                continue;
            }
            hits.add(new Hit(p, RelativeLocation.pickAnchor(p, universe,
                    RelativeLocation.diagonal(sizes.get(p.map())))));
        }

        // 排序：同名优先（问「铁矿」时先给就叫「铁矿」的），其次按离参照物的距离
        hits.sort(Comparator
                .comparingInt((Hit h) -> h.place().name() != null
                        && h.place().name().toLowerCase(Locale.ROOT).equals(t.toLowerCase(Locale.ROOT)) ? 0 : 1)
                .thenComparingDouble(h -> h.neighbour() == null ? Double.MAX_VALUE : h.neighbour().distance()));
        if (hits.size() > want) {
            hits = new ArrayList<>(hits.subList(0, want));
        }
        return new Answer(t, hits, "");
    }
}
