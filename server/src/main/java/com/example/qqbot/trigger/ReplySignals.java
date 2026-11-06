package com.example.qqbot.trigger;

import java.util.List;
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
        String t = text == null ? "" : text.strip();
        if (t.isEmpty() || t.codePointCount(0, t.length()) < 3 || isNotQuestion(t)) {
            return false;
        }
        return QUESTION_TAIL.matcher(t).find()
                || QUESTION_PARTICLE.matcher(t).find()
                || STRICT_LEAD.matcher(t).find();
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
