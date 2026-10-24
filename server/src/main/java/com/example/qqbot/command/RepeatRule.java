package com.example.qqbot.command;

import java.time.DayOfWeek;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 定时公告的重复规则 —— {@code #重复-每天#} 那一格。
 *
 * <h2>为什么单独一个类型</h2>
 * 「每天」「每周三」「每3天」看着都是两个汉字，但落到调度上是三种完全不同的算法。
 * 把它们压成字符串抛给动作，等于把三种算法的分支散进业务代码里。
 *
 * <p>认不出来就报错，不猜。猜错的代价是一条公告发错时间，或者永远不发。
 *
 * @param kind        重复方式
 * @param everyDays   {@link Kind#EVERY_N_DAYS} 时的天数，其余情况为 0
 * @param dayOfWeek   {@link Kind#WEEKLY} 时的星期，其余情况为 null
 * @param repeatCount {@link Kind#TIMES} 时的次数（天数），其余情况为 0。
 *                    「3 次」指 3 天，每天按时间表里的所有时刻各发一次 ——
 *                    与「一次」是同一个口径：一次指一天
 */
public record RepeatRule(Kind kind, int everyDays, DayOfWeek dayOfWeek, int repeatCount) {

    /** 兼容旧构造：没有次数 */
    public RepeatRule(Kind kind, int everyDays, DayOfWeek dayOfWeek) {
        this(kind, everyDays, dayOfWeek, 0);
    }

    public RepeatRule {
        repeatCount = Math.max(repeatCount, 0);
    }

    public enum Kind {
        /** 只发一次 */
        ONCE,
        /** 每天同一时刻 */
        DAILY,
        /** 每周某一天 */
        WEEKLY,
        /** 每 N 天 */
        EVERY_N_DAYS,
        /** 共 N 次：从第一次的时刻起按天发，发满 N 天就结束 */
        TIMES
    }

    private static final String[] CN_DIGITS = {"零", "一", "二", "三", "四", "五", "六", "七", "八", "九", "十"};
    private static final String[] WEEK_NAMES = {"一", "二", "三", "四", "五", "六", "日"};
    /** 「3次」「三次」「十五次」 */
    private static final Pattern TIMES = Pattern.compile("^(\\d{1,3}|[零一二两三四五六七八九十]+)次$");

    public static RepeatRule once() {
        return new RepeatRule(Kind.ONCE, 0, null);
    }

    public static RepeatRule daily() {
        return new RepeatRule(Kind.DAILY, 0, null);
    }

    /** 共 N 次，N 至少 2（1 次就是 {@link #once()}） */
    public static RepeatRule times(int count) throws CommandParseException {
        if (count < 2 || count > 99) {
            throw new CommandParseException("重复", "「N 次」的 N 要在 2 到 99 之间");
        }
        return new RepeatRule(Kind.TIMES, 0, null, count);
    }

    /**
     * 解析群友写的重复方式。
     *
     * <p>认这些写法：一次 / 3次 / 每天 / 每日 / 每周三 / 每周3 / 每周7 / 每周天 /
     * 每3天 / 每三天 / 每十五天 / 每30天。
     *
     * <p>「N 次」是有限周期：从第一次的时刻起每天发一次，发满 N 天结束；
     * 与「一次」同口径 —— 一次指一天，那天时间表里有几个时刻就各发一次。
     *
     * <p>星期几接受 1 到 7、一 到 六、日 / 天 / 七 / 7；「每 N 天」的 N 接受
     * 阿拉伯数字与中文数字，范围 2 到 30 —— 1 天就是每天，直接写「每天」。
     *
     * <p>认不出来就报错并指出他写的是什么 —— 静默当成"一次"最危险，
     * 那样一条本该每天发的公告会只发一次，而且没人知道为什么。
     */
    public static RepeatRule parse(String raw) throws CommandParseException {
        if (raw == null || raw.isBlank()) {
            throw new CommandParseException("重复",
                    "缺少 #重复#，可以写：一次 / 3次 / 每天 / 每周三 / 每3天");
        }
        String s = raw.trim().toLowerCase(Locale.ROOT);
        // 先认落库形态，再认群友手写的说法
        if (s.startsWith("weekly:")) {
            return new RepeatRule(Kind.WEEKLY, 0, DayOfWeek.of(numberOf(s.substring(7), 1, 7, raw)));
        }
        if (s.startsWith("every:")) {
            return new RepeatRule(Kind.EVERY_N_DAYS, numberOf(s.substring(6), 2, 30, raw), null);
        }
        if (s.startsWith("times:")) {
            return times(numberOf(s.substring(6), 2, 99, raw));
        }
        if (s.equals("一次") || s.equals("只一次") || s.equals("once")) {
            return once();
        }
        if (s.equals("每天") || s.equals("每日") || s.equals("daily")) {
            return daily();
        }
        if (TIMES.matcher(s).matches()) {
            int count = number(s.substring(0, s.length() - 1));
            if (count == 1) {
                return once();
            }
            return times(count);
        }
        if (s.startsWith("每周") || s.startsWith("周") || s.startsWith("week")
                || s.startsWith("每星期") || s.startsWith("每礼拜")) {
            return weekly(s);
        }
        if (s.startsWith("每")) {
            return everyDays(s);
        }
        throw new CommandParseException("重复",
                "没看懂「" + raw + "」，可以写：一次 / 每天 / 每周三 / 每3天");
    }

    private static RepeatRule weekly(String s) throws CommandParseException {
        String rest = s.replaceFirst("^每星期", "").replaceFirst("^每礼拜", "")
                .replaceFirst("^每周", "").replaceFirst("^周", "")
                .replaceFirst("^week", "");
        rest = rest.replace("星期", "").replace("礼拜", "").trim();
        if (rest.isEmpty()) {
            throw new CommandParseException("重复", "「每周」后面要跟星期几，例：每周三");
        }
        int idx = weekIndex(rest.charAt(0));
        if (idx < 0) {
            throw new CommandParseException("重复", "没看懂「" + s + "」是周几，例：每周三");
        }
        return new RepeatRule(Kind.WEEKLY, 0, DayOfWeek.of(idx + 1));
    }

    private static RepeatRule everyDays(String s) throws CommandParseException {
        String rest = s.replaceFirst("^每", "").replaceFirst("天$", "").trim();
        if (rest.contains("周") || rest.contains("星期") || rest.contains("礼拜")) {
            throw new CommandParseException("重复",
                    "暂不支持「每 N 周」，可以写「每周几」或者「每 N 天」");
        }
        int n = number(rest);
        if (n == 1) {
            throw new CommandParseException("重复", "每 1 天就是每天，写「每天」就行");
        }
        if (n < 2 || n > 30) {
            throw new CommandParseException("重复",
                    "每几天发一次，天数要在 2 到 30 之间，例：每3天、每十五天");
        }
        return new RepeatRule(Kind.EVERY_N_DAYS, n, null);
    }

    /** 周几：一 到 六 与 日/天/七/7，都指周日 */
    private static int weekIndex(char c) {
        if (c == '日' || c == '天' || c == '七' || c == '7') {
            return 6;
        }
        if (c >= '1' && c <= '6') {
            return c - '1';
        }
        for (int i = 0; i < WEEK_NAMES.length; i++) {
            if (WEEK_NAMES[i].charAt(0) == c) {
                return i;
            }
        }
        return -1;
    }

    /**
     * 阿拉伯数字或中文数字（两、十一、二十、二十三、三十）。
     *
     * <p>「每十五天」以前会走进「十 到 十九」那条特判，二十以上则直接失败；
     * 这里统一按「十位 + 个位」拆，2 到 30 全都能写。
     */
    private static int number(String s) {
        if (s == null || s.isEmpty()) {
            return -1;
        }
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException ignored) {
            // 走中文
        }
        if (s.equals("两")) {
            return 2;
        }
        int tenAt = s.indexOf('十');
        if (tenAt < 0) {
            return digit(s);
        }
        int tens = tenAt == 0 ? 1 : digit(s.substring(0, tenAt));
        int ones = tenAt == s.length() - 1 ? 0 : digit(s.substring(tenAt + 1));
        if (tens < 1 || tens > 9 || ones < 0) {
            return -1;
        }
        return tens * 10 + ones;
    }

    /** 零到九的中文数字；不认识返回 -1 */
    private static int digit(String s) {
        for (int i = 0; i < CN_DIGITS.length; i++) {
            if (CN_DIGITS[i].equals(s)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * 落库形态。
     *
     * <p>{@code weekly:3} / {@code every:3} 这种能原样读回来，
     * 而群友写的中文只用来输入 —— 库里存的东西必须机器读得懂。
     */
    public String toStorage() {
        return switch (kind) {
            case ONCE -> "once";
            case DAILY -> "daily";
            case WEEKLY -> "weekly:" + dayOfWeek.getValue();
            case EVERY_N_DAYS -> "every:" + everyDays;
            case TIMES -> "times:" + repeatCount;
        };
    }

    private static int numberOf(String s, int min, int max, String raw) throws CommandParseException {
        try {
            int n = Integer.parseInt(s.trim());
            if (n >= min && n <= max) {
                return n;
            }
        } catch (NumberFormatException ignored) {
            // 落到下面统一报错
        }
        throw new CommandParseException("重复", "没看懂「" + raw + "」");
    }

    /**
     * 落库形态转人话，列表里显示 {@code {params.重复}} 用。
     *
     * <p>库里存的是 once / daily / weekly:3 / every:5，群友看到的应该是
     * 只发一次 / 每天 / 每周三 / 每 5 天。认不出来就原样返回 —— 显示层不能因为
     * 一条脏数据把整个列表打断。
     */
    public static String describeStored(String raw) {
        if (raw == null || raw.isBlank()) {
            return raw == null ? "" : raw;
        }
        try {
            return parse(raw).describe();
        } catch (CommandParseException e) {
            return raw;
        }
    }

    /** 回执里那句人话 */
    public String describe() {
        return switch (kind) {
            case ONCE -> "只发一次";
            case DAILY -> "每天";
            case WEEKLY -> "每周" + WEEK_NAMES[dayOfWeek.getValue() - 1];
            case EVERY_N_DAYS -> "每 " + everyDays + " 天";
            case TIMES -> "共 " + repeatCount + " 次";
        };
    }
}
