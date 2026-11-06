package com.example.qqbot.intent;

/**
 * 一轮请求的判定结果 —— 交给 {@code ChatService} 使用。
 *
 * @param move       识别出的意图
 * @param retrieve   这次要不要检索知识库
 * @param correcting 这是对上一轮回答的纠正
 * @param reason     判定理由，只进日志，不进提示词
 * @param chimeIn    这是机器人主动接话，不是被点名
 */
public record TurnPlan(TurnMove move, boolean retrieve, boolean correcting, String reason,
                       boolean chimeIn) {

    /** 兼容老调用方：不是主动接话 */
    public TurnPlan(TurnMove move, boolean retrieve, boolean correcting, String reason) {
        this(move, retrieve, correcting, reason, false);
    }

    /** 默认：不干预，与接入前完全一致 */
    public static TurnPlan standard() {
        return new TurnPlan(TurnMove.DEFAULT, true, false, "默认");
    }

    /** 群聊闲聊接话：不检索，只接一句 */
    public static TurnPlan chime() {
        return new TurnPlan(TurnMove.CHIME, false, false, "群聊插话", true);
    }

    /** 疑问接话：保留原来的检索决定，只标记「不是被点名」 */
    public TurnPlan withChime() {
        return chimeIn ? this : new TurnPlan(move, retrieve, correcting, reason, true);
    }

    public boolean skipsRetrieval() {
        return !retrieve;
    }
}
