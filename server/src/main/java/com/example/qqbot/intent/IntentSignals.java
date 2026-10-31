package com.example.qqbot.intent;

import java.util.List;
import java.util.regex.Pattern;

/**
 * 关键词与词形的**唯一来源** —— 纯函数，没有 Spring、没有 IO、没有状态。
 *
 * <h2>为什么只认高置信形态</h2>
 * 规则误判的代价是"少查一次资料"，这比答错轻，但也不能乱认。实测过的误伤：
 * <ul>
 *   <li>"你说一下长守镇的主人是谁" —— 光认"主人"会当成社交，其实是在问地名</li>
 *   <li>"你会帮群主女装吗" —— 光认"你会"会当成元问题</li>
 *   <li>"看看腿，我的意思是看看雾锁里面…腿部装备" —— 上半句像骚扰，下半句自我修正成了装备问题</li>
 * </ul>
 * 所以：社交类要求完整的称呼形态（"叫我主人"），元问题要求以"你是/你有"开头，
 * 而且**先看有没有自我修正**（"我的意思是"）—— 有就一律按默认处理，不猜。
 *
 * <p>判不出来的就是 {@link TurnMove#DEFAULT}，走原来的链路。宁可漏判，不要误判。
 */
public final class IntentSignals {

    /** 说了半句又改口：按上半句判必然错，直接放弃判定 */
    private static final Pattern SELF_CORRECTION = Pattern.compile("我的意思是|我是说|我说错了|我打错了|别误会");

    /** 对上一轮回答的纠正 */
    private static final List<Pattern> CORRECTION = List.of(
            Pattern.compile("又说错|你错了|说错了|搞错|我说的是|明明(是|有)"),
            Pattern.compile("不是.{0,12}[吗嘛]"));

    /** 做不到的请求：让人帮着上班、生成图片、下单 */
    private static final List<Pattern> OUT_OF_SCOPE = List.of(
            Pattern.compile("(帮我|给我|替我)(上班|加班|写作业|做作业|下单|点外卖|买|偷|揍|肝|撸)"),
            Pattern.compile("(帮我|给我).{0,10}点(一|个|杯|份)"),
            Pattern.compile("(生成|画).{0,20}(图|视频|照片|头像)"),
            Pattern.compile("发个.{0,6}(照|图)"),
            Pattern.compile("看看(腿|照片|胸)"));

    /** 关于 bot 自己 */
    private static final List<Pattern> META = List.of(
            Pattern.compile("^(你|您)(是|叫)(谁|什么)"),
            Pattern.compile("你是(机器人|ai|AI|真的|假的|真人)"),
            Pattern.compile("你(现在)?(有|会)(什么|啥|哪些)(能力|功能|本领)"),
            Pattern.compile("你(现在)?有啥能力"),
            Pattern.compile("^(在吗|在不在)$"),
            Pattern.compile("(介绍|说说)(一下)?你自己"));

    /** 社交：卖萌、撒娇、骂人、角色扮演 */
    private static final List<Pattern> SOCIAL = List.of(
            Pattern.compile("卖萌|撒娇|卖个萌|夸我|夸夸我"),
            Pattern.compile("叫(我|你).{0,4}(主人|爸爸|哥哥|姐姐|老婆|老公)"),
            Pattern.compile("亲亲|抱抱|摸摸|哄睡|贴贴"),
            Pattern.compile("女装|小男娘|南梁|涩涩|腹黑|猫娘"),
            Pattern.compile("唱(一|个|首)|唱歌|跳舞|学.{0,2}叫"),
            Pattern.compile("^(你|你们)(是|真)?(废物|傻子|笨蛋|垃圾|坏蛋)"),
            Pattern.compile("玩坏|调教|当我.{0,2}主人"));

    private IntentSignals() {
    }

    /**
     * 判一个提问的意图。
     *
     * <p>顺序是有意的：先放弃（自我修正），再认最不可能撞车的纠正，然后才轮到宽泛的社交。
     */
    public static TurnMove classify(String question) {
        if (question == null) {
            return TurnMove.DEFAULT;
        }
        String q = question.strip();
        if (q.isEmpty()) {
            return TurnMove.DEFAULT;
        }
        if (SELF_CORRECTION.matcher(q).find()) {
            return TurnMove.DEFAULT;
        }
        if (matches(CORRECTION, q)) {
            return TurnMove.CORRECT;
        }
        if (matches(OUT_OF_SCOPE, q)) {
            return TurnMove.OUT_OF_SCOPE;
        }
        if (matches(META, q)) {
            return TurnMove.META;
        }
        if (matches(SOCIAL, q)) {
            return TurnMove.SOCIAL;
        }
        return TurnMove.DEFAULT;
    }

    private static boolean matches(List<Pattern> patterns, String q) {
        for (Pattern p : patterns) {
            if (p.matcher(q).find()) {
                return true;
            }
        }
        return false;
    }
}
