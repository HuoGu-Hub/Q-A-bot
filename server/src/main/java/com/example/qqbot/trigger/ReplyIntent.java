package com.example.qqbot.trigger;

/**
 * 一条群消息在「要不要主动回复」这件事上的形态。
 *
 * <p>它不是检索意图 {@link com.example.qqbot.intent.TurnMove}：TurnMove 决定
 * 「给模型看什么」，这里决定「这条消息值不值得接」。两者刻意分开，因为
 * 接话判定必须在调模型之前完成，而且不能依赖检索或模型。
 */
public enum ReplyIntent {

    /** 明确在叫机器人：@、正文昵称、引用机器人 */
    CALL,

    /** 命中后台配置的关键词；低概率档唯一的非疑问入口 */
    KEYWORD,

    /** 看起来在问群里：问号、疑问词、求助词 */
    QUESTION,

    /** 有文字，但不像在提问 */
    CHAT,

    /** 没有文字、纯图、纯链接、两个字以内、纯复读 */
    NOISE
}
