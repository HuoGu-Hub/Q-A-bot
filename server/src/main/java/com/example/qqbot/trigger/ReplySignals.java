package com.example.qqbot.trigger;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 触发判定的**关键词与词形** —— 纯函数，没有 Spring、没有 IO、没有状态。
 *
 * <p>它只分三档：{@link ReplyIntent#QUESTION}、{@link ReplyIntent#CHAT}、
 * {@link ReplyIntent#NOISE}。呼叫由路由层先判，不在这里。
 *
 * <p>判定原则：宁可漏判，不要误判。把一条闲聊判成疑问的代价是「多回一条」，
 * 把疑问判成闲聊的代价是「少回一条」，后者更轻。
 */
public final class ReplySignals {

    private ReplySignals() {
    }

    /** 纯链接：没有可接的话 */
    private static final Pattern URL = Pattern.compile("(?i)^(https?://|www\\.)\\S+$");

    /** 复读、笑声、单字刷屏 */
    private static final Pattern PURE_REPEAT = Pattern.compile(
            "(?i)^(h{3,}|哈{3,}|2{3,}|6{2,}|233+|awsl|xswl|草|hhh|笑死|绷不住)[!！。.~～]*$");

    /** 反问、吐槽、否定：形式像问句，实际不是在提问 */
    private static final List<Pattern> NOT_QUESTION = List.of(
            Pattern.compile("怎么会|怎么就|怎么又|怎么这么|怎么那么|怎么这样"),
            Pattern.compile("没什么|没啥|有什么好|什么鬼|哪怕"),
            Pattern.compile("不是.{0,12}[吗嘛]"));

    /** 疑问词。只收高置信词形，不收单独的「谁」 */
    private static final Pattern QUESTION_WORD = Pattern.compile(
            "(什么|啥|咋|怎么|为什么|为啥|如何|哪(里|儿|个|些|边)?|"
                    + "多少|几点|几个|多大|多久|谁(知道|能|会)|"
                    + "是不是|有没有|能不能|可不可以|要不要|会不会|行不行|对不对|"
                    + "请问|求问|求教|求带|求组|求推荐|有人知道|"
                    + "帮我看看|帮忙看|查一下|找一下)");

    /** 高置信疑问词：问的是什么比较明确 */
    private static final Pattern HIGH_QUESTION_WORD = Pattern.compile(
            "(为什么|为啥|怎么|如何|哪(里|儿|个|些|边)|多少|几点|几个|多大|多久|"
                    + "谁(知道|能|会)|请问|求问|求教|求带|求组|求推荐|有人知道|"
                    + "帮我看看|帮忙看|查一下|找一下)");

    /** 低置信疑问词：单字或范围太宽，低档不认 */
    private static final Pattern LOW_QUESTION_WORD = Pattern.compile(
            "(什么|啥|咋|是不是|有没有|能不能|可不可以|要不要|会不会|行不行|对不对|谁)");

    /** 句末问号 */
    private static final Pattern QUESTION_TAIL = Pattern.compile("[?？]\\s*$");

    /** 句末「吗 / 嘛 / 呢」 */
    private static final Pattern QUESTION_PARTICLE = Pattern.compile("(吗|嘛|呢)\\s*$");

    /**
     * 严格疑问的开头：疑问词/求助词必须出现在句首。
     * 低概率档只认这种，避免「煤矿在哪里啊」这种把疑问词埋在陈述句里的消息被抢答。
     */
    private static final Pattern STRICT_LEAD = Pattern.compile(
            "^(请问|求问|求教|求助|求带|求组|求推荐|有人知道|有没有人|谁知道|谁懂|"
                    + "怎么|为什么|为啥|如何|哪(里|儿|个|些|边)|多少|几点|几个|多大|多久|"
                    + "什么|啥|咋|是不是|有没有|能不能|可不可以|要不要|会不会|行不行|对不对|"
                    + "帮我看看|帮忙看|查一下|找一下)");

    /**
     * 判一条消息的形态。
     *
     * @param text    消息文字，已剥离 @ 前缀
     * @param hasImage 消息里有没有图片
     * @param atOther 有没有 @ 别人
     */
    public static ReplyIntent classify(String text, boolean hasImage, boolean atOther) {
        String t = text == null ? "" : text.strip();
        if (t.isEmpty() || t.codePointCount(0, t.length()) < 3) {
            return ReplyIntent.NOISE;
        }
        // 未命中的斜杠指令不参与闲聊，避免机器人对 /foo 随机接话
        if (t.startsWith("/")) {
            return ReplyIntent.NOISE;
        }
        if (t.codePoints().noneMatch(Character::isLetterOrDigit)) {
            return ReplyIntent.NOISE;
        }
        if (URL.matcher(t).matches() || PURE_REPEAT.matcher(t).matches()) {
            return ReplyIntent.NOISE;
        }
        if (isNotQuestion(t)) {
            return hasImage ? ReplyIntent.NOISE : ReplyIntent.CHAT;
        }
        if (atOther) {
            // 在问别人，不是问机器人。低 p 时不该抢答。
            return hasImage ? ReplyIntent.NOISE : ReplyIntent.CHAT;
        }
        if (isQuestion(t)) {
            return ReplyIntent.QUESTION;
        }
        return hasImage ? ReplyIntent.NOISE : ReplyIntent.CHAT;
    }

    /**
     * 严格疑问：句末问号/吗嘛呢，或疑问词出现在句首。
     *
     * <p>「这个钥匙哪里拿」是宽松疑问，不是严格疑问；低概率档不接。
     */
    public static boolean isStrictQuestion(String text) {
        return strictQuestionEvidence(text) != null;
    }

    /**
     * 严格疑问的具体依据，给记录页展示"为什么算严格疑问"。
     * 返回 null 表示不是严格疑问。
     */
    public static String strictQuestionEvidence(String text) {
        String t = text == null ? "" : text.strip();
        if (t.isEmpty() || t.codePointCount(0, t.length()) < 3 || isNotQuestion(t)) {
            return null;
        }
        if (QUESTION_TAIL.matcher(t).find()) {
            return "问号结尾";
        }
        var particle = QUESTION_PARTICLE.matcher(t);
        if (particle.find()) {
            return "「" + particle.group(1) + "」结尾";
        }
        var lead = STRICT_LEAD.matcher(t);
        if (lead.find()) {
            return "句首「" + lead.group(1) + "」";
        }
        return null;
    }

    /**
     * 宽松疑问的具体依据；不是疑问时返回 null。
     * 严格疑问会优先给出严格依据。
     */
    public static String questionEvidence(String text) {
        String t = text == null ? "" : text.strip();
        if (t.isEmpty() || t.codePointCount(0, t.length()) < 3 || !isQuestion(t) || isNotQuestion(t)) {
            return null;
        }
        String strict = strictQuestionEvidence(t);
        if (strict != null) {
            return strict;
        }
        var word = QUESTION_WORD.matcher(t);
        if (word.find()) {
            return "命中「" + word.group(1) + "」";
        }
        return "疑问";
    }

    /**
     * 严格疑问的强度：3 = 句首疑问词或问号，2 = 只有句末「吗/嘛/呢」。
     * 0 表示不是严格疑问。
     */
    public static int strictQuestionStrength(String text) {
        String t = text == null ? "" : text.strip();
        String evidence = strictQuestionEvidence(t);
        if (evidence == null) {
            return 0;
        }
        if (QUESTION_TAIL.matcher(t).find() || STRICT_LEAD.matcher(t).find()) {
            return 3;
        }
        return 2;
    }

    /**
     * 宽松疑问的强度：3 = 严格疑问，2 = 高置信疑问词，1 = 低置信疑问词。
     * 0 表示不是疑问。
     */
    public static int looseQuestionStrength(String text) {
        String t = text == null ? "" : text.strip();
        if (t.isEmpty() || t.codePointCount(0, t.length()) < 3 || isNotQuestion(t) || !isQuestion(t)) {
            return 0;
        }
        if (strictQuestionEvidence(t) != null) {
            return 3;
        }
        if (HIGH_QUESTION_WORD.matcher(t).find()) {
            return 2;
        }
        if (LOW_QUESTION_WORD.matcher(t).find()) {
            return 1;
        }
        return 0;
    }

    /** 游戏相关词命中：分数 0-4，term 是命中的词 */
    public record GameMatch(int score, String term) {
        public boolean hit() {
            return score > 0;
        }
    }

    /**
     * 判断一句话和游戏的相关程度。
     *
     * <p>两张词表都由后台配置传入，**不存在隐藏词表**：
     * 强相关 4 分，一般相关 2 分；只取最高分，不叠加。
     */
    public static GameMatch gameRelevance(String text, List<String> strong,
                                          List<String> general) {
        String t = text == null ? "" : text.strip().toLowerCase(Locale.ROOT);
        if (t.isEmpty()) {
            return new GameMatch(0, "");
        }
        for (String term : safe(strong)) {
            if (containsTerm(t, term)) {
                return new GameMatch(4, term.trim());
            }
        }
        for (String term : safe(general)) {
            if (containsTerm(t, term)) {
                return new GameMatch(2, term.trim());
            }
        }
        return new GameMatch(0, "");
    }

    private static List<String> safe(List<String> terms) {
        return terms == null ? List.of() : terms;
    }

    private static boolean containsTerm(String text, String term) {
        if (term == null || term.isBlank()) {
            return false;
        }
        String k = term.strip().toLowerCase(Locale.ROOT);
        boolean ascii = k.codePoints().allMatch(c -> c < 128);
        if (ascii) {
            return Pattern.compile("(?<![a-z0-9])" + Pattern.quote(k) + "(?![a-z0-9])")
                    .matcher(text).find();
        }
        return text.contains(k);
    }

    private static boolean isQuestion(String t) {
        return QUESTION_TAIL.matcher(t).find()
                || QUESTION_PARTICLE.matcher(t).find()
                || QUESTION_WORD.matcher(t).find();
    }

    private static boolean isNotQuestion(String t) {
        for (Pattern p : NOT_QUESTION) {
            if (p.matcher(t).find()) {
                return true;
            }
        }
        return false;
    }

    /**
     * 正文里有没有机器人昵称。
     *
     * <p>名字为空时返回 false。否则 {@code contains("")} 永远为 true，
     * 启动阶段还没拿到昵称时会把每条消息都当成呼叫。
     */
    public static boolean mentionsName(String text, String name) {
        return text != null && name != null && !name.isBlank() && text.contains(name);
    }
}
