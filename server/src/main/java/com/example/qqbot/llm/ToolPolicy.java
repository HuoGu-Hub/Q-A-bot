package com.example.qqbot.llm;

/**
 * 工具调用预算的**形状** —— 对应 {@code app.llm.tool.*}。
 *
 * <p>为什么要有它：模型能多轮调用检索工具之后，"为了答得更好"就会想一直查下去
 * （比如查到配方里有铁与布料，再去查铁怎么获得、布料怎么获得）。
 * 这本身是好事，但**响应时间不可控**。所以两道闸都必须有：
 * <ul>
 *   <li>{@code deadlineSeconds} 管"别卡太久" —— 到点就不再执行工具，模型用已有资料作答；</li>
 *   <li>{@code maxCalls} 管"别问太多" —— 次数用尽同理。</li>
 * </ul>
 *
 * <p>只卡一个都会漏：只卡次数，某次检索特别慢照样超时；只卡时间，快的时候它会问十几次。
 */
public class ToolPolicy {

    /** 单次请求的总时限（秒）。默认 30 */
    private int deadlineSeconds = 30;

    /** 单次请求最多调几次工具。默认 3 */
    private int maxCalls = 3;

    /**
     * 业务意义上的"最多几轮工具调用"。默认 3。
     *
     * <p>到达这个轮数后，下一轮不再给模型工具，只让它用已查到的资料作答。
     * 交给 langchain4j 的 {@code maxToolCallingRoundTrips} 时必须 **+1**：
     * 框架的检查发生在响应返回之后，正好会在"收尾那一轮"抛异常，把答案丢掉。
     * 见 {@link KbToolAssistant#frameworkRoundTripLimit(ToolPolicy)}。
     */
    private int maxRoundTrips = 3;

    public int getDeadlineSeconds() {
        return deadlineSeconds;
    }

    public void setDeadlineSeconds(int deadlineSeconds) {
        this.deadlineSeconds = deadlineSeconds;
    }

    public int getMaxCalls() {
        return maxCalls;
    }

    public void setMaxCalls(int maxCalls) {
        this.maxCalls = maxCalls;
    }

    public int getMaxRoundTrips() {
        return maxRoundTrips;
    }

    public void setMaxRoundTrips(int maxRoundTrips) {
        this.maxRoundTrips = maxRoundTrips;
    }
}
