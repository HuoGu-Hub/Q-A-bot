package com.example.qqbot.kb;

/**
 * 标题闸门（C 路）。
 *
 * <p><b>为什么它是独立的一个接缝，而不是又一条召回路径</b>：闸门命中的块
 * <b>不参与 RRF 投票</b>，而是直接置顶。理由见 {@link KbRetriever} 的类注释 ——
 * 正文路在"用名字提问"上是系统性错的，让它平等参与融合会把错误答案抬上来。
 * 把闸门伪装成一条召回路径，融合器就得靠 {@code source} 字符串去猜哪一条是闸门，
 * 那种隐式约定迟早会错。
 */
public interface KbTitleGateSource {

    /**
     * 判定这次提问是不是"就是在问某个名字"。
     *
     * @param threshold 闸门阈值；{@code <= 0} 表示关掉 C 路
     * @return 命中与否、最高标题余弦（**不管有没有过闸门**，调阈值看的就是它）
     */
    KbRetriever.TitleGate check(KbRetrievalContext context, double threshold);
}
