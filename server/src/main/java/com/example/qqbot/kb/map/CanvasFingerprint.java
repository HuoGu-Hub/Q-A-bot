package com.example.qqbot.kb.map;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 底图几何指纹 —— 决定"一个世界坐标落在底图的哪个像素上"的**全部输入**。
 *
 * <h2>它解决什么问题</h2>
 * 管理员在地图上标的点位，存的是 {@code (map, page, x, y)}。其中 x/y 是**世界坐标**，
 * 而世界坐标到屏幕像素的换算要靠画布自己声明的取景窗口：
 * <pre>
 *   worldOf(canvas, x, y) = [x - topLeft.x, topLeft.y - y]
 * </pre>
 * 也就是说：<b>同一个 x/y，画布的取景窗口一变，落点就整体跑偏。</b>
 * 而画布的那些数字全是从 wiki 同步来的 —— 对方什么时候换图、换谁维护、按什么范围导，
 * 我们一概不知道，也不会有人通知我们。
 *
 * <p>所以每个自定义点位在**标记的那一刻**顺手抄一份当时的指纹存下来。
 * 之后每次读点位时比对：不一样就说明底图变了，这个点需要人复核一眼。
 * 它<b>不保证点位不错位</b>（对方换了坐标系，任何自动换算都需要一份只有人知道的对应关系），
 * 它保证的是<b>错位这件事是可见的</b>，而不是安安静静地指错地方。
 *
 * <h2>为什么是这几个字段</h2>
 * <ul>
 *   <li>{@code topLeft} / {@code bottomRight} —— 取景窗口。变了两图之间就不在一个坐标系上；</li>
 *   <li>{@code tileAt} / {@code tileSize} —— 世界坐标 → 画布像素的基准。瓦片换了切法，
 *       即使窗口没变，点位也会偏；</li>
 *   <li>{@code backgroundImage} —— 整图形态的底图身份，文件名换了说明对方重新出过图；</li>
 *   <li>{@code schema} —— wiki 的制图规范版本，从 v17 升到 v17.3 这类改动往往伴随重新导出。</li>
 * </ul>
 *
 * <h2>已知的边界</h2>
 * 瓦片形态的底图**看不到文件内容的变化**：对方把 {@code 0_0.jpg} 重新导出一遍，
 * 文件名没变、窗口没变，指纹就一样。这种"地形改了但坐标系没改"的情况本来就无法自动判断，
 * 只能靠人复核 —— 而这正是 {@link MapPlaceService#pendingReview()} 存在的意义。
 */
final class CanvasFingerprint {

    private CanvasFingerprint() {
    }

    /**
     * 算一张画布的指纹。画布为 {@code null}（页面在库里找不到）时返回空串 ——
     * 空串在比对时一律算"需要复核"，因为没有任何东西能证明这个点位还是对的。
     */
    static String of(KbMapStore.Canvas c) {
        if (c == null) {
            return "";
        }
        return String.join("|",
                c.hasCrs() ? "crs" : "noc",
                pair(c.topLeft()),
                pair(c.bottomRight()),
                pair(c.tileAt()),
                pair(c.tileSize()),
                nz(c.backgroundImage()),
                schemaTail(c.schema()));
    }

    /**
     * 两个指纹的差异说明 —— 给管理端列表用，让人一眼知道"变了哪一块"。
     *
     * @return 没有差异时返回空串
     */
    static String describe(String oldFp, String newFp) {
        String a = nz(oldFp);
        String b = nz(newFp);
        if (a.equals(b)) {
            return "";
        }
        if (a.isEmpty()) {
            return "这个点位没登记过底图指纹，请确认位置";
        }
        if (b.isEmpty()) {
            return "找不到它所在的画布了（页面可能被改名或删除）";
        }
        String[] x = a.split("\\|", -1);
        String[] y = b.split("\\|", -1);
        if (x.length != y.length) {
            return "底图定义变了";
        }
        List<String> why = new ArrayList<>();
        if (!x[1].equals(y[1]) || !x[2].equals(y[2])) {
            why.add("取景窗口变了");
        }
        if (!x[3].equals(y[3]) || !x[4].equals(y[4])) {
            why.add("切图基准变了");
        }
        if (!x[5].equals(y[5])) {
            why.add("底图文件换了");
        }
        if (!x[6].equals(y[6])) {
            why.add("wiki 制图规范版本变了");
        }
        return why.isEmpty() ? "底图定义变了" : String.join("、", why);
    }

    /** 坐标对转成稳定字符串。收两位小数：更小的差值在 10240 的图上不到一个像素 */
    private static String pair(double[] v) {
        if (v == null || v.length < 2) {
            return "-";
        }
        return String.format(Locale.ROOT, "%.2f,%.2f", v[0], v[1]);
    }

    /** schema 是一整条 URL，只留末尾的文件名 —— 指纹要短到能放进日志和界面 */
    private static String schemaTail(String schema) {
        String s = nz(schema);
        int slash = s.lastIndexOf('/');
        return slash >= 0 ? s.substring(slash + 1) : s;
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
