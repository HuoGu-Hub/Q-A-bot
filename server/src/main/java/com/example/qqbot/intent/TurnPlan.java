package com.example.qqbot.intent;

/**
 * 一轮请求的判定结果 —— {@link IntentPlanner} 的产出，交给 {@code ChatService} 使用。
 *
 * @param move       识别出的意图
 * @param retrieve   这次要不要检索知识库
 * @param correcting 这是对上一轮回答的纠正（第三轮提交接上）
 * @param reason     判定理由，只进日志，不进提示词
 */
public record TurnPlan(TurnMove move, boolean retrieve, boolean correcting, String reason) {

    /** 默认：不干预，与接入前完全一致 */
    public static TurnPlan standard() {
        return new TurnPlan(TurnMove.DEFAULT, true, false, "默认");
    }

    public boolean skipsRetrieval() {
        return !retrieve;
    }
}
