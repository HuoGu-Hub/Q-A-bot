package com.example.qqbot.command;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 命令识别 —— 从消息文本里判断「这是不是一条命令，是哪一条」。
 *
 * <pre>
 *   ① 群里必须 @ 机器人（由路由层用准入设置判，见 MessageRouter）
 *   ② 必须以 "/" 开头
 *   ③ 命令名就是路径：按【最长匹配】一级一级吃词，吃不动就停
 *   ④ 路径之后若紧跟 /help，认作单命令帮助修饰符，不执行
 * </pre>
 *
 * <h2>为什么是「吃词」而不是「整词相等」</h2>
 * 命令可以分层：{@code /set /output} 是一条两级命令。匹配时从第一段开始，
 * 只要「当前路径 + 下一个以 / 开头的词」在指令表里就继续并进去，否则停下。
 * 于是 {@code /联网 /api/getUser 是什么} 的路径停在「联网」，
 * {@code /api/getUser 是什么} 完整留作文本 —— 贴路径依旧不会误触发。
 *
 * <h2>本类只做识别</h2>
 * 参数怎么拆、值怎么校验都在 {@link CommandParser}。这里只回答「是哪条命令、
 * 尾巴从哪儿开始、是不是在要帮助」。
 */
@Component
public class CommandMatcher {

    private final CommandPolicy props;
    private final CommandStore store;

    public CommandMatcher(CommandPolicy props, CommandStore store) {
        this.props = props;
        this.store = store;
    }

    /**
     * 匹配结果。
     *
     * @param command    命中的指令；未命中为 null
     * @param looksLike  是否"看起来像命令"（以 / 开头）—— 即使没配也要记录，
     *                   用来发现该加哪些命令
     * @param rawTrigger 解析出的命令路径，用于日志与未命中统计
     * @param rest       路径之后、/help 之后的原文，交给 {@link CommandParser}
     * @param help       是否请求了单命令帮助
     */
    public record Match(BotCommand command, boolean looksLike, String rawTrigger, String rest,
                        boolean help) {

        public static Match none() {
            return new Match(null, false, null, "", false);
        }

        /** 看起来像命令但没命中：记一笔，但不返回命令 */
        public static Match miss(String path, String rest) {
            return new Match(null, true, path, rest == null ? "" : rest, false);
        }

        public boolean hit() {
            return command != null;
        }

        /** 是不是在要这一条命令的帮助：{@code /路径 /help} */
        public boolean helpRequested() {
            return help;
        }

        /** 路径之后的原文，永不为 null —— 调用方少一层判空 */
        public String restOrEmpty() {
            return rest == null ? "" : rest;
        }

        /** 老名字，等价于 {@link #restOrEmpty()} */
        public String argsOrEmpty() {
            return restOrEmpty();
        }
    }

    /**
     * 判断一条消息是不是命令。
     *
     * @param text    消息正文（已 trim）
     * @param inGroup 是否群聊
     * @param groupId 群号（私聊传 0）
     */
    public Match match(String text, boolean inGroup, long groupId) {
        if (!props.isEnabled() || !store.isAvailable() || !StringUtils.hasText(text)) {
            return Match.none();
        }

        String body = text.trim();
        if (!body.startsWith("/")) {
            return Match.none();
        }

        String[] parts = body.split("\\s+");
        // 首词去掉开头的斜杠就是路径候选；允许 set/output 这种连写
        String path = parts[0].substring(1);
        if (path.isEmpty()) {
            return Match.none();
        }

        BotCommand cmd = store.findByTrigger(path);
        int consumed = 1;

        // 最长匹配：能吃进路径的词就吃，吃不动就停
        while (consumed < parts.length && parts[consumed].startsWith("/")) {
            String seg = parts[consumed].substring(1);
            // help 是保留字，交给后面的帮助分支；空段说明用户多打了斜杠
            if (seg.isEmpty() || "help".equalsIgnoreCase(seg)) {
                break;
            }
            String deeperPath = path + "/" + seg;
            BotCommand deeper = store.findByTrigger(deeperPath);
            if (deeper == null) {
                break;
            }
            path = deeperPath;
            cmd = deeper;
            consumed++;
        }

        if (cmd == null || !cmd.enabled()) {
            return Match.miss(path, join(parts, consumed));
        }
        // 作用范围校验
        if (inGroup && !cmd.appliesToGroup(groupId)) {
            return Match.miss(path, join(parts, consumed));
        }
        if (!inGroup && !cmd.appliesToPrivate()) {
            return Match.miss(path, join(parts, consumed));
        }

        boolean help = consumed < parts.length && "/help".equalsIgnoreCase(parts[consumed]);
        if (help) {
            consumed++;
        }
        return new Match(cmd, true, path, join(parts, consumed), help);
    }

    private static String join(String[] parts, int from) {
        if (from >= parts.length) {
            return "";
        }
        return String.join(" ", java.util.Arrays.copyOfRange(parts, from, parts.length)).trim();
    }
}
