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

    /** 强游戏词：游戏名、资料片名、本群专属地名 */
    private static final List<String> STRONG_GAME_TERMS = List.of(
            "雾锁王国", "雾锁", "enshrouded", "我的世界", "minecraft",
            "泰拉瑞亚", "terraria", "星露谷物语", "星露谷", "stardew",
            "幻兽帕鲁", "palworld", "艾尔登法环", "elden ring", "黑神话",
            "博德之门", "塞尔达", "原神", "崩坏", "星穹铁道",
            "魔兽世界", "world of warcraft", "最终幻想14", "ff14",
            "英雄联盟", "league of legends", "无畏契约", "valorant",
            "csgo", "cs2", "apex", "永劫无间", "命运方舟",
            "流放之路", "path of exile", "暗黑破坏神", "diablo",
            "饥荒", "don't starve", "英灵神殿", "valheim", "七日杀",
            "7 days to die", "方舟生存", "ark survival", "腐蚀", "rust",
            "星界边境", "starbound", "煤矿");

    /** 通用游戏词：可能出现在非游戏语境，权重低一档 */
    private static final List<String> GENERIC_GAME_TERMS = List.of(
            "游戏", "开服", "服务器", "联机", "存档", "模组", "mods", "mod",
            "副本", "boss", "npc", "任务", "装备", "武器", "技能", "天赋",
            "职业", "等级", "经验", "掉落", "合成", "配方", "矿洞", "挖矿",
            "矿石", "矿物", "钥匙", "机关", "地图", "坐标", "传送", "建造",
            "基地", "采集", "制作", "刷怪", "组队", "队友", "房主",
            "steam", "epic", "手柄");

    /** 弱游戏词：单独出现时不足以说明是游戏问题 */
    private static final List<String> WEAK_GAME_TERMS = List.of(
            "卡顿", "帧数", "显卡", "客户端", "版本", "更新", "补丁", "报错",
            "崩溃", "启动", "安装", "开门", "这门", "门怎么", "门在哪",
            "打怪", "怪", "材料", "资源");

    /**
     * 判断一句话和游戏的相关程度。
     *
     * <p>强词 4 分，通用词 2 分，弱词 1 分；配置里的词按强词算。
     * 只取最高分，不叠加，避免一句话里堆几个泛词就冒充强相关。
     */
    public static GameMatch gameRelevance(String text, List<String> extra) {
        String t = text == null ? "" : text.strip().toLowerCase(Locale.ROOT);
        if (t.isEmpty()) {
            return new GameMatch(0, "");
        }
        if (extra != null) {
            for (String term : extra) {
                if (containsTerm(t, term)) {
                    return new GameMatch(4, term.strip());
                }
            }
        }
        GameMatch best = new GameMatch(0, "");
        for (String term : STRONG_GAME_TERMS) {
            if (containsTerm(t, term)) {
                return new GameMatch(4, term);
            }
        }
        for (String term : GENERIC_GAME_TERMS) {
            if (containsTerm(t, term)) {
                best = new GameMatch(2, term);
                break;
            }
        }
        if (best.hit()) {
            return best;
        }
        for (String term : WEAK_GAME_TERMS) {
            if (containsTerm(t, term)) {
                return new GameMatch(1, term);
            }
        }
        return new GameMatch(0, "");
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
