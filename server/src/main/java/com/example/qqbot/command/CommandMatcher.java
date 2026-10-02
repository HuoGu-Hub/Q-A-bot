package com.example.qqbot.command;

import com.example.qqbot.config.CommandProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 指令匹配 —— 从消息文本里判断"这是不是一条指令"。
 *
 * <pre>
 *   ① 群里必须 @ 机器人（可配置）
 *   ② 必须是 "/" 开头（可配置）
 *   ③ 首词与触发词【完全相等】（大小写不敏感），**后面可以跟参数**
 * </pre>
 *
 * <p><b>2026-09-28 起支持参数</b>：原来是"去掉 / 之后必须完全相等"，
 * 所以 {@code /报名 张三} 永远匹配不上。小活动和"换知识来源"的指令都要参数，
 * 于是改成「首词精确 + 余下都是参数」。
 *
 * <p><b>严格性没有放松</b>：只有**首词**参与匹配，所以贴路径
 * （{@code /api/getUser foo}、{@code /usr/local/bin}）依旧不会误触发 ——
 * 它们的首词根本不在指令表里。真正被这条规则挡住的只有"触发词后面接东西"，
 * 而那正是我们要放开的东西。
 */
@Component
public class CommandMatcher {

    /** 匹配 "/xxx"：斜杠后跟非空白字符 */
    private static final Pattern SLASH_COMMAND = Pattern.compile("^/(\\S+)\\s*$");

    private final CommandProperties props;
    private final CommandStore store;

    public CommandMatcher(CommandProperties props, CommandStore store) {
        this.props = props;
        this.store = store;
    }

    /**
     * 匹配结果。
     *
     * @param command    命中的指令；未命中为 null
     * @param looksLike  是否是"看起来像指令"（"/" 开头）——
     *                   即使没配也要记录，用来发现该加哪些命令
     * @param rawTrigger 去掉 "/" 后的**首词**（用于日志与未命中统计）
     * @param args       首词之后的全部内容（已 trim；没有则为空串）
     */
    public record Match(BotCommand command, boolean looksLike, String rawTrigger, String args) {

        public static Match none() {
            return new Match(null, false, null, "");
        }

        public static Match miss(String raw) {
            return new Match(null, true, raw, "");
        }

        public boolean hit() {
            return command != null;
        }

        /** 参数，永不为 null —— 调用方少一层判空 */
        public String argsOrEmpty() {
            return args == null ? "" : args;
        }
    }

    /**
     * 判断一条消息是否触发指令。
     *
     * @param text       消息正文（已 trim）
     * @param mentioned  群里是否 @ 了机器人（私聊传 true）
     * @param inGroup    是否群聊
     * @param groupId    群号（私聊传 0）
     */
    public Match match(String text, boolean mentioned, boolean inGroup, long groupId) {
        if (!props.isEnabled() || !store.isAvailable() || !StringUtils.hasText(text)) {
            return Match.none();
        }

        // ① 群里必须 @
        if (inGroup && props.isRequireMention() && !mentioned) {
            return Match.none();
        }

        String body = text.trim();

        // ② 必须 "/" 开头
        if (props.isRequireSlash()) {
            if (!body.startsWith("/")) {
                return Match.none();
            }
            body = body.substring(1);
        }

        // ③ 拆成「首词 + 参数」
        String rest = body.trim();
        if (rest.isEmpty()) {
            return Match.none();
        }
        int sp = firstWhitespace(rest);
        String trigger = sp < 0 ? rest : rest.substring(0, sp);
        String args = sp < 0 ? "" : rest.substring(sp + 1).trim();
        if (trigger.isEmpty()) {
            return Match.none();
        }

        BotCommand cmd = store.findByTrigger(trigger);
        if (cmd == null) {
            return Match.miss(trigger);
        }
        if (!cmd.enabled()) {
            return Match.miss(trigger);
        }
        // 作用范围校验
        if (inGroup && !cmd.appliesToGroup(groupId)) {
            return Match.miss(trigger);
        }
        if (!inGroup && !cmd.appliesToPrivate()) {
            return Match.miss(trigger);
        }
        return new Match(cmd, true, trigger, args);
    }

    /** 第一个空白字符的位置（空格 / 制表符都算），没有返回 -1 */
    private static int firstWhitespace(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (Character.isWhitespace(s.charAt(i))) {
                return i;
            }
        }
        return -1;
    }

    /**
     * 从消息里提取"看起来像指令"的部分（即使没 @）。
     *
     * <p>只用于**统计**：帮我们发现"群友在群里发 /xxx，
     * 但我们没配、或者他忘了 @"。
     */
    public String extractSlashWord(String text) {
        if (!StringUtils.hasText(text)) {
            return null;
        }
        String body = text.trim();
        if (!body.startsWith("/")) {
            return null;
        }
        Matcher m = SLASH_COMMAND.matcher(body.replaceAll("^@\\S+\\s*", ""));
        if (m.matches()) {
            return m.group(1).toLowerCase(java.util.Locale.ROOT);
        }
        // "/帮助 额外内容" 也提取首个词
        String rest = body.substring(1).trim();
        int sp = firstWhitespace(rest);
        String word = sp > 0 ? rest.substring(0, sp) : rest;
        return word.isBlank() ? null : word.toLowerCase(java.util.Locale.ROOT);
    }
}
