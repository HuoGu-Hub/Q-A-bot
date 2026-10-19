package com.example.qqbot.command;

import java.time.DayOfWeek;
import java.util.Locale;

/**
 * 定时公告的重复规则 —— {@code #重复-每天#} 那一格。
 *
 * <h2>为什么单独一个类型</h2>
 * 「每天」「每周三」「每3天」看着都是两个汉字，但落到调度上是三种完全不同的算法。
 * 把它们压成字符串抛给动作，等于把三种算法的分支散进业务代码里。
 *
 * <p>认不出来就报错，不猜。猜错的代价是一条公告发错时间，或者永远不发。
 *
 * @param kind     重复方式
 * @param everyDays {@link Kind#EVERY_N_DAYS} 时的天数，其余情况为 0
 * @param dayOfWeek {@link Kind#WEEKLY} 时的星期，其余情况为 null
 */
public record RepeatRule(Kind kind, int everyDays, DayOfWeek dayOfWeek) {

    public enum Kind {
        /** 只发一次 */
        ONCE,
        /** 每天同一时刻 */
        DAILY,
        /** 每周某一天 */
        WEEKLY,
        /** 每 N 天 */
        EVERY_N_DAYS
    }

    private static final String[] CN_DIGITS = {"零", "一", "二", "三", "四", "五", "六", "七", "八", "九", "十"};
    private static final String[] WEEK_NAMES = {"一", "二", "三", "四", "五", "六", "日"};

    public static RepeatRule once() {
        return new RepeatRule(Kind.ONCE, 0, null);
    }

    public static RepeatRule daily() {
        return new RepeatRule(Kind.DAILY, 0, null);
    }

    /**
     * 解析群友写的重复方式。
     *
     * <p>认这些写法：一次 / 每天 / 每日 / 每周三 / 每周天 / 每3天 / 每三天。
     * 认不出来就报错并指出他写的是什么 —— 静默当成"一次"最危险，
     * 那样一条本该每天发的公告会只发一次，而且没人知道为什么。
     */
    public static RepeatRule parse(String raw) throws CommandParseException {
        if (raw == null || raw.isBlank()) {
            throw new CommandParseException("重复", "缺少 #重复#，可以写：一次 / 每天 / 每周三 / 每3天");
        }
        String s = raw.trim().toLowerCase(Locale.ROOT);
        // 先认落库形态，再认群友手写的说法
        if (s.startsWith("weekly:")) {
            return new RepeatRule(Kind.WEEKLY, 0, DayOfWeek.of(numberOf(s.substring(7), 1, 7, raw)));
        }
        if (s.startsWith("every:")) {
            return new RepeatRule(Kind.EVERY_N_DAYS, numberOf(s.substring(6), 2, 30, raw), null);
        }
        if (s.equals("一次") || s.equals("只一次") || s.equals("once")) {
            return once();
        }
        if (s.equals("每天") || s.equals("每日") || s.equals("daily")) {
            return daily();
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
        int n = number(rest);
        if (n < 2 || n > 30) {
            throw new CommandParseException("重复", "每几天发一次，天数要在 2 到 30 之间，例：每3天");
        }
        return new RepeatRule(Kind.EVERY_N_DAYS, n, null);
    }

    /** 周几：一 到 六 与 日/天 */
    private static int weekIndex(char c) {
        if (c == '日' || c == '天' || c == '7') {
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

    /** 阿拉伯数字或一到十的中文数字 */
    private static int number(String s) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException ignored) {
            // 走中文
        }
        if (s.equals("两")) {
            return 2;
        }
        for (int i = 0; i < CN_DIGITS.length; i++) {
            if (CN_DIGITS[i].equals(s)) {
                return i;
            }
        }
        // 十一 到 十九
        if (s.startsWith("十") && s.length() == 2) {
            for (int i = 0; i < CN_DIGITS.length; i++) {
                if (CN_DIGITS[i].equals(s.substring(1))) {
                    return 10 + i;
                }
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

    /** 回执里那句人话 */
    public String describe() {
        return switch (kind) {
            case ONCE -> "只发一次";
            case DAILY -> "每天";
            case WEEKLY -> "每周" + WEEK_NAMES[dayOfWeek.getValue() - 1];
            case EVERY_N_DAYS -> "每 " + everyDays + " 天";
        };
    }
}
