package com.example.qqbot.intent;

/**
 * 意图判定的**只读配置视图** —— 由 {@code config.IntentProperties} 实现。
 *
 * <p>为什么不直接注入 properties：ArchUnit 的「业务包不得直接依赖 config」盯着这条线，
 * 配置只该在装配层注入。这与 {@code llm.LlmPolicy} 是同一个做法。
 *
 * <p>必须是接口而不是快照：配置是热生效的，{@code SettingsService} 就地改字段，
 * 这里每次现读，后台改完下一条消息就变。
 */
public interface IntentPolicy {

    /** 总开关。关掉之后判定不参与，行为与接入前一致 */
    boolean isEnabled();

    /** 社交整活不检索：卖萌、撒娇、夸我、叫主人、亲亲抱抱、唱歌、骂机器人 */
    boolean isSkipSocial();

    /** 关于机器人自己不检索：你是谁、你叫什么、你有什么能力、在吗、介绍你自己 */
    boolean isSkipMeta();

    /** 做不到的请求不检索：帮你上班/写作业/下单、生成图片、发照片、看看腿 */
    boolean isSkipOutOfScope();

    /** 识别纠正（第三轮提交生效） */
    boolean isCorrection();

    /** 回看几轮 */
    int getRecentTurns();

    /** 上一轮多久之内还算数 */
    int getRecentMinutes();
}
