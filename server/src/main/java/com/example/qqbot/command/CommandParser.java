package com.example.qqbot.command;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;
import java.util.regex.Pattern;

/**
 * 命令尾部的解析 —— 把「#名字-值# 自由文本」拆成参数与文本。
 *
 * <h2>职责边界</h2>
 * 它<b>不认识指令表</b>，只认识参数表。路径怎么匹配是 {@link CommandMatcher} 的事，
 * 命中之后把尾巴交给这里。这样「命令怎么写」和「参数怎么填」可以分别改、分别测。
 *
 * <h2>三件容易做错的事</h2>
 * <ol>
 *   <li><b>只有形状正确的井号段才是参数</b>。没有减号的 {@code #1#}、名字里带空格的
 *       {@code #a b-1#}，一律原样留在文本里。群里说「打#1#」不该被当成参数错误。</li>
 *   <li><b>摘走参数不能在文本里留下空格</b>。做法是按参数段切开、每段去首尾空白、
 *       丢掉空段、再用一个空格接回去 —— 值内部与文本内部的空格原样保留。</li>
 *   <li><b>变量展开必须在类型校验之前</b>。{@code #time-{time}#} 先变成 21:30 才可能
 *       通过时间校验；反过来永远过不了。</li>
 * </ol>
 */
@Component
public class CommandParser {

    /** 参数名：汉字、字母、数字、下划线；不含减号、井号、空白 */
    private static final Pattern NAME = Pattern.compile("[\\p{IsHan}A-Za-z0-9_]+");

    /** 参数段之间的分隔符用全角减号也认 —— 中文输入法下很容易打出来 */
    private static final String DASHES = "-－—";

    /**
     * 多值参数的分隔符。
     *
     * <p>三种都认：顿号、全角逗号、半角逗号 —— 中文输入法下打出来哪个都不奇怪。
     * 只有声明了「可多值」的参数才会被切，所以文本参数里的顿号仍然是顿号。
     */
    private static final Pattern MULTI_SEP = Pattern.compile("[、，,]");

    /**
     * 解析结果。
     *
     * @param args 按名字取到的参数
     * @param text 摘掉参数段之后的自由文本，已做空白归一
     */
    public record Result(CommandArgs args, String text) {
    }

    /** 一次摘出来的原始参数段 */
    private record RawParam(String name, String value) {
    }

    /** 切开之后的两半：参数段与文本片段 */
    private record Sliced(List<RawParam> params, List<String> texts) {
    }

    /** 用当前时刻解析，测试请走带 {@code now} 的重载 */
    public Result parse(String raw, List<ParamSpec> specs, UnaryOperator<String> expand)
            throws CommandParseException {
        return parse(raw, specs, expand, Instant.now());
    }

    /**
     * 解析命令尾巴。
     *
     * @param raw    路径之后的原文
     * @param specs  参数表；为空表示这条命令不吃参数，整段都当文本
     * @param expand 变量展开函数，用在值上；为 null 表示不展开
     * @param now    当前时刻，用于时间与日期参数
     */
    public Result parse(String raw, List<ParamSpec> specs, UnaryOperator<String> expand, Instant now)
            throws CommandParseException {
        String body = raw == null ? "" : raw.trim();
        List<ParamSpec> table = specs == null ? List.of() : specs;

        // 没有参数表的命令不摘井号段：老指令的文本里出现 #xxx# 不该被判成参数错误
        if (table.isEmpty()) {
            return new Result(CommandArgs.empty(), body);
        }

        Sliced sliced = slice(body);
        Map<String, String> values = bind(sliced.params(), table);
        CommandArgs args = convert(values, table, expand, now);
        return new Result(args, joinText(sliced.texts()));
    }

    // ==================== 摘取 ====================

    /**
     * 从头扫一遍，把形状正确的 {@code #名字-值#} 摘出来，其余原样留作文本。
     *
     * <p>{@code \#} 是转义，代表一个字面井号；没有收尾井号的整段也当字面量。
     */
    private Sliced slice(String body) {
        List<RawParam> params = new ArrayList<>();
        List<String> texts = new ArrayList<>();
        StringBuilder chunk = new StringBuilder();

        int i = 0;
        int len = body.length();
        while (i < len) {
            char c = body.charAt(i);

            if (c == '\\' && i + 1 < len) {
                char next = body.charAt(i + 1);
                if (next == '#' || next == '＃') {
                    chunk.append('#');
                    i += 2;
                    continue;
                }
                if (next == '\\') {
                    chunk.append('\\');
                    i += 2;
                    continue;
                }
                chunk.append(c);
                i++;
                continue;
            }

            if (c == '#' || c == '＃') {
                int close = findClose(body, i + 1);
                if (close < 0) {
                    chunk.append(c);
                    i++;
                    continue;
                }
                RawParam p = parseParam(body.substring(i + 1, close));
                if (p == null) {
                    // 形状不符：整段当普通文本，连井号一起留下
                    chunk.append(body, i, close + 1);
                    i = close + 1;
                    continue;
                }
                texts.add(chunk.toString());
                chunk.setLength(0);
                params.add(p);
                i = close + 1;
                continue;
            }

            chunk.append(c);
            i++;
        }
        texts.add(chunk.toString());
        return new Sliced(params, texts);
    }

    /** 找收尾井号；转义的井号不算收尾 */
    private static int findClose(String body, int from) {
        for (int i = from; i < body.length(); i++) {
            char c = body.charAt(i);
            if (c == '\\' && i + 1 < body.length()) {
                i++;
                continue;
            }
            if (c == '#' || c == '＃') {
                return i;
            }
        }
        return -1;
    }

    /** 拆「名字-值」；形状不对返回 null，表示这段不是参数 */
    private static RawParam parseParam(String inner) {
        int dash = -1;
        for (int i = 0; i < inner.length(); i++) {
            if (DASHES.indexOf(inner.charAt(i)) >= 0) {
                dash = i;
                break;
            }
        }
        if (dash < 0) {
            return null;
        }
        String name = inner.substring(0, dash).trim();
        if (name.isEmpty() || !NAME.matcher(name).matches()) {
            return null;
        }
        return new RawParam(name, inner.substring(dash + 1).trim());
    }

    /**
     * 文本片段接回去。
     *
     * <p>每段先去首尾空白再丢空段，段与段之间只留一个空格 —— 这样
     * 「#t-1#   文本」不会把参数前后的空白带进文本，而「晚上  打本」
     * 这种本来就写在同一段里的空格原样保留。
     */
    private static String joinText(List<String> texts) {
        StringBuilder sb = new StringBuilder();
        for (String t : texts) {
            String s = t == null ? "" : t.trim();
            if (s.isEmpty()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(s);
        }
        return sb.toString();
    }

    // ==================== 校验与转换 ====================

    /** 按名字收参数：未知、重复、缺必填都在这里拦下 */
    private Map<String, String> bind(List<RawParam> params, List<ParamSpec> table)
            throws CommandParseException {
        Map<String, ParamSpec> byName = new LinkedHashMap<>();
        for (ParamSpec s : table) {
            byName.put(s.name(), s);
        }

        Map<String, String> values = new LinkedHashMap<>();
        for (RawParam p : params) {
            ParamSpec spec = byName.get(p.name());
            if (spec == null) {
                throw new CommandParseException(p.name(),
                        "没有 #" + p.name() + "# 这个参数，可用：" + names(table));
            }
            if (values.containsKey(p.name())) {
                throw new CommandParseException(p.name(), "#" + p.name() + "# 给了两次");
            }
            values.put(p.name(), p.value());
        }

        for (ParamSpec s : table) {
            if (!values.containsKey(s.name()) && s.hasDefault()) {
                values.put(s.name(), s.defaultValue());
            }
        }
        for (ParamSpec s : table) {
            if (s.required() && !values.containsKey(s.name())) {
                throw new CommandParseException(s.name(), "缺少必填参数 #" + s.name() + "#");
            }
        }
        return values;
    }

    /** 展开变量、按类型转值 */
    private CommandArgs convert(Map<String, String> values, List<ParamSpec> table,
                                UnaryOperator<String> expand, Instant now) throws CommandParseException {
        Map<String, String> raw = new LinkedHashMap<>();
        Map<String, Object> typed = new LinkedHashMap<>();
        for (ParamSpec s : table) {
            String v = values.get(s.name());
            if (v == null) {
                continue;
            }
            String expanded = expand == null ? v : expand.apply(v);
            if (expanded == null) {
                expanded = "";
            }
            if (s.multiple()) {
                // 多值：切开、逐个校验。raw 存整段，{arg.时间} 显示的就是群友打的那一串
                List<String> pieces = splitMulti(s, expanded);
                List<Object> typedValues = new ArrayList<>(pieces.size());
                for (String piece : pieces) {
                    typedValues.add(convertOne(s, truncate(s, piece), now));
                }
                raw.put(s.name(), expanded);
                typed.put(s.name(), List.copyOf(typedValues));
                continue;
            }
            // 截断要在存 raw 之前做：模板取值的 {arg.x} 与动作拿到的必须是同一份值，
            // 否则长度保护只在动作那一侧生效，模板仍旧能把超长参数放出去
            String cut = truncate(s, expanded);
            raw.put(s.name(), cut);
            typed.put(s.name(), convertOne(s, cut, now));
        }
        return new CommandArgs(raw, typed);
    }

    /**
     * 把一段值切成多个。空片段直接丢掉，「7:00、、14:00」这种手抖不算错。
     *
     * <p>切完一个都不剩才报错 —— 那说明他写了个只有逗号的东西。
     */
    private static List<String> splitMulti(ParamSpec spec, String value) throws CommandParseException {
        List<String> pieces = new ArrayList<>();
        for (String piece : MULTI_SEP.split(value)) {
            String p = piece.trim();
            if (!p.isEmpty()) {
                pieces.add(p);
            }
        }
        if (pieces.isEmpty()) {
            throw new CommandParseException(spec.name(), "#" + spec.name() + "# 没有值");
        }
        if (pieces.size() > ParamSpec.MAX_VALUES) {
            throw new CommandParseException(spec.name(),
                    "#" + spec.name() + "# 最多 " + ParamSpec.MAX_VALUES + " 个值，这次给了 "
                            + pieces.size() + " 个");
        }
        return pieces;
    }

    /** 文本参数超长截断；其余类型不截 */
    private static String truncate(ParamSpec spec, String value) {
        if (spec.type() == ParamType.TEXT && spec.maxChars() > 0 && value.length() > spec.maxChars()) {
            return value.substring(0, spec.maxChars());
        }
        return value;
    }

    private Object convertOne(ParamSpec spec, String value, Instant now) throws CommandParseException {
        ParamType type = spec.type();
        if (type == ParamType.TEXT) {
            // 长度已在上面截断过，这里只负责形状
            return value;
        }
        if (type == ParamType.INT) {
            try {
                return Long.parseLong(value.trim());
            } catch (NumberFormatException e) {
                throw new CommandParseException(spec.name(),
                        "#" + spec.name() + "# 的 " + value + " 不是整数" + example(spec));
            }
        }
        if (type == ParamType.MOMENT) {
            try {
                return MomentParser.parseMoment(value, now);
            } catch (CommandParseException e) {
                throw new CommandParseException(spec.name(),
                        "#" + spec.name() + "# 的 " + value + " 不是有效时间" + example(spec));
            }
        }
        if (type == ParamType.DATE) {
            try {
                return MomentParser.parseDate(value, now);
            } catch (CommandParseException e) {
                throw new CommandParseException(spec.name(),
                        "#" + spec.name() + "# 的 " + value + " 不是有效日期" + example(spec));
            }
        }
        // ENUM
        if (spec.options().isEmpty()) {
            return value;
        }
        for (String opt : spec.options()) {
            if (opt.equalsIgnoreCase(value.trim())) {
                return opt;
            }
        }
        throw new CommandParseException(spec.name(),
                "#" + spec.name() + "# 只能是 " + String.join(" / ", spec.options()));
    }

    private static String example(ParamSpec spec) {
        return spec.example() == null || spec.example().isBlank() ? "" : "，例：" + spec.example();
    }

    private static String names(List<ParamSpec> table) {
        List<String> out = new ArrayList<>(table.size());
        for (ParamSpec s : table) {
            out.add("#" + s.name() + "#");
        }
        return String.join(" ", out);
    }

}
