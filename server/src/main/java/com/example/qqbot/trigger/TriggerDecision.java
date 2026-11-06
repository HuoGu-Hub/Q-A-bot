package com.example.qqbot.trigger;

/**
 * 一次触发判定的结果。
 *
 * @param reply      真的要不要回复。影子模式下恒为 false
 * @param wouldReply 判定层面想不想回。影子模式下可能为 true
 * @param intent     消息形态
 * @param chance     这一次的回复概率，0 到 1
 * @param roll       这一次抽到的随机数，0 到 1
 * @param reason     只进日志与丢弃原因，不进提示词
 */
public record TriggerDecision(boolean reply, boolean wouldReply, ReplyIntent intent,
                              double chance, double roll, String reason) {

    /** 呼叫直通，不参与概率 */
    public static TriggerDecision call(String reason) {
        return new TriggerDecision(true, true, ReplyIntent.CALL, 1.0, 0.0, reason);
    }

    /** 判定通过，真的回复 */
    public static TriggerDecision yes(ReplyIntent intent, double chance, double roll) {
        return new TriggerDecision(true, true, intent, chance, roll, "命中");
    }

    /** 判定通过，但影子模式只记日志 */
    public static TriggerDecision would(ReplyIntent intent, double chance, double roll) {
        return new TriggerDecision(false, true, intent, chance, roll, "影子模式");
    }

    /** 判定不通过 */
    public static TriggerDecision no(ReplyIntent intent, String reason) {
        return no(intent, 0.0, 0.0, reason);
    }

    /** 判定不通过，但保留这次的概率与掷点 —— 记录里要能看出"差一点"和"根本没机会" */
    public static TriggerDecision no(ReplyIntent intent, double chance, double roll, String reason) {
        return new TriggerDecision(false, false, intent, chance, roll, reason);
    }

    public boolean isCall() {
        return intent == ReplyIntent.CALL;
    }
}
