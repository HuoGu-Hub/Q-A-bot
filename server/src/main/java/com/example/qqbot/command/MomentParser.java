package com.example.qqbot.command;

import com.example.qqbot.time.AppTime;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 时间参数解析 —— 把群友说的「明天晚上9点半之后」换成确定的时刻。
 *
 * <p>只认 <b>AppTime 那一套东八区</b>：解析、落库、展示、扫描全链路一致，
 * 不引入第二套换算。中国没有夏令时，所以这里不需要 tzdata。
 *
 * <p>支持的写法都是群里真会打出来的那几种：
 * <pre>
 *   21:30            今天 21:30；已经过了就顺延到明天
 *   今晚21:30 明天9:00 后天20:00
 *   周三21:30 下周三21:30
 *   10-18 21:30      今年 10 月 18 日
 *   2026-10-18 21:30
 *   3小时后 30分钟后 2天后
 * </pre>
 *
 * <p><b>只给日期不给时间是要报错的</b>，不做「默认零点」这种猜测 ——
 * 零点推送一条消息比不推送更糟。
 */
public final class MomentParser {

    private MomentParser() {
    }

    /** 相对时长：3小时后 / 30分钟 / 2天之后 */
    private static final Pattern RELATIVE = Pattern.compile("^(\\d{1,4})\\s*(分钟|小时|天)\\s*(?:后|之后|以后)?$");
    /** 末尾的时刻，前面是日期表达式：整体拆成「日期部分 + H:mm」 */
    private static final Pattern TAIL_CLOCK = Pattern.compile("^(.*?)\\s*(\\d{1,2})[:：](\\d{1,2})$");
    private static final Pattern FULL_DATE = Pattern.compile("^(\\d{4})\\s*[-/年]\\s*(\\d{1,2})\\s*[-/月]\\s*(\\d{1,2})\\s*日?$");
    private static final Pattern SHORT_DATE = Pattern.compile("^(\\d{1,2})\\s*[-/月]\\s*(\\d{1,2})\\s*日?$");
    private static final Pattern WEEKDAY = Pattern.compile("^(下)?\\s*(?:周|星期|礼拜)\\s*([一二三四五六日天1-7])$");

    /** 日期是怎么定下来的 —— 决定「已过」时该顺延一天还是一周 */
    private enum DateKind { IMPLICIT, DAY_WORD, WEEKDAY, EXPLICIT }

    private record Resolved(LocalDate date, DateKind kind) {
    }

    /** 解析时刻；失败抛出带中文原因的异常 */
    public static Instant parseMoment(String raw, Instant now) throws CommandParseException {
        String s = normalize(raw);
        if (s.isEmpty()) {
            throw new CommandParseException(null, "时间是空的");
        }

        Matcher rel = RELATIVE.matcher(s);
        if (rel.matches()) {
            long n = Long.parseLong(rel.group(1));
            return switch (rel.group(2)) {
                case "分钟" -> now.plus(Duration.ofMinutes(n));
                case "小时" -> now.plus(Duration.ofHours(n));
                default -> now.plus(Duration.ofDays(n));
            };
        }

        Matcher tail = TAIL_CLOCK.matcher(s);
        if (!tail.matches()) {
            throw new CommandParseException(null, "「" + s + "」没写时间，例：21:30 或 明天21:30");
        }
        Resolved day = resolveDate(tail.group(1), now);
        int hour = parseInt(tail.group(2), "小时");
        int minute = parseInt(tail.group(3), "分钟");
        if (hour > 23 || minute > 59) {
            throw new CommandParseException(null, "「" + s + "」不是有效时间");
        }

        ZonedDateTime at = day.date().atTime(LocalTime.of(hour, minute)).atZone(AppTime.ZONE);
        Instant instant = at.toInstant();
        if (instant.isBefore(now)) {
            // 只写时刻时顺延一天；写了周几则顺延一周；写了明确日期就不动，让调用方去拒绝
            if (day.kind() == DateKind.IMPLICIT) {
                instant = at.plusDays(1).toInstant();
            } else if (day.kind() == DateKind.WEEKDAY) {
                instant = at.plusWeeks(1).toInstant();
            }
        }
        return instant;
    }

    /** 解析日期；失败抛出带中文原因的异常 */
    public static LocalDate parseDate(String raw, Instant now) throws CommandParseException {
        String s = normalize(raw);
        if (s.isEmpty()) {
            throw new CommandParseException(null, "日期是空的");
        }
        Matcher rel = RELATIVE.matcher(s);
        if (rel.matches() && "天".equals(rel.group(2))) {
            return now.atZone(AppTime.ZONE).toLocalDate().plusDays(Long.parseLong(rel.group(1)));
        }
        return resolveDate(s, now).date();
    }

    // ==================== 内部 ====================

    private static Resolved resolveDate(String expr, Instant now) throws CommandParseException {
        String s = expr == null ? "" : expr.trim();
        LocalDate today = now.atZone(AppTime.ZONE).toLocalDate();
        if (s.isEmpty() || "今天".equals(s) || "今日".equals(s) || "今晚".equals(s)) {
            return new Resolved(today, DateKind.IMPLICIT);
        }
        if ("明天".equals(s) || "明早".equals(s) || "明晚".equals(s)) {
            return new Resolved(today.plusDays(1), DateKind.DAY_WORD);
        }
        if ("后天".equals(s) || "后日".equals(s)) {
            return new Resolved(today.plusDays(2), DateKind.DAY_WORD);
        }

        Matcher wk = WEEKDAY.matcher(s);
        if (wk.matches()) {
            DayOfWeek target = toDayOfWeek(wk.group(2));
            long ahead = (target.getValue() - today.getDayOfWeek().getValue() + 7) % 7;
            if (wk.group(1) != null) {
                ahead += 7;
            }
            return new Resolved(today.plusDays(ahead), DateKind.WEEKDAY);
        }

        Matcher full = FULL_DATE.matcher(s);
        if (full.matches()) {
            return new Resolved(of(parseInt(full.group(1), "年"),
                    parseInt(full.group(2), "月"), parseInt(full.group(3), "日"), s), DateKind.EXPLICIT);
        }

        Matcher shortDate = SHORT_DATE.matcher(s);
        if (shortDate.matches()) {
            int month = parseInt(shortDate.group(1), "月");
            int day = parseInt(shortDate.group(2), "日");
            LocalDate date = of(today.getYear(), month, day, s);
            // 不写年份时，已经过去的日子按明年算 —— 否则「10-18」在 10-19 说就永远定不出来
            if (date.isBefore(today)) {
                date = date.plusYears(1);
            }
            return new Resolved(date, DateKind.EXPLICIT);
        }

        throw new CommandParseException(null, "看不懂的日期「" + s + "」，例：10-18 或 明天");
    }

    private static DayOfWeek toDayOfWeek(String token) throws CommandParseException {
        return switch (token) {
            case "一", "1" -> DayOfWeek.MONDAY;
            case "二", "2" -> DayOfWeek.TUESDAY;
            case "三", "3" -> DayOfWeek.WEDNESDAY;
            case "四", "4" -> DayOfWeek.THURSDAY;
            case "五", "5" -> DayOfWeek.FRIDAY;
            case "六", "6" -> DayOfWeek.SATURDAY;
            case "日", "天", "7" -> DayOfWeek.SUNDAY;
            default -> throw new CommandParseException(null, "看不懂的星期「" + token + "」");
        };
    }

    /** 组装日期；2 月 30 日这类非法组合要变成能回执的错误，不是 DateTimeException */
    private static LocalDate of(int year, int month, int day, String raw) throws CommandParseException {
        try {
            return LocalDate.of(year, month, day);
        } catch (Exception e) {
            throw new CommandParseException(null, "「" + raw + "」不是有效日期");
        }
    }

    private static int parseInt(String s, String label) throws CommandParseException {
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            throw new CommandParseException(null, label + "「" + s + "」不是数字");
        }
    }

    /** 全角数字与全角冒号在中文输入法下极常见，先归一再匹配 */
    private static String normalize(String raw) {
        if (raw == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(raw.length());
        for (char c : raw.trim().toCharArray()) {
            if (c >= '０' && c <= '９') {
                sb.append((char) ('0' + (c - '０')));
            } else if (c == '：') {
                sb.append(':');
            } else if (c == '／') {
                sb.append('/');
            } else if (c == '　') {
                sb.append(' ');
            } else {
                sb.append(c);
            }
        }
        return sb.toString().trim().toLowerCase(Locale.ROOT);
    }
}
