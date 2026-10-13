package com.example.qqbot.kb;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import org.springframework.stereotype.Component;

/**
 * 只读工具：查知识库、查地图点位。
 *
 * <p><b>第一批工具只开只读</b>：模型能调工具之后，"群友说一句话"就成了触发面。
 * 改数据、发消息、动配置这类能力一律不放进第一批 —— 先证明授权与循环是可靠的。
 *
 * <h2>为什么"位置"要单独一个工具，而不是让模型读检索到的坐标</h2>
 * 地点语料里确实有坐标，但模型没法可靠地比较 1700 个点的距离 ——
 * 它会把"算方位"变成"编方位"，而且编得很像。{@link #locate} 把这一步接过来：
 * 入参是词，出参是**算好的**方向和距离，模型只负责组织语言。
 *
 * <p>两个工具都只读，且都不接受"要写到哪"这类参数 —— 工具的能力边界由签名决定。
 */
@Component
public class KbLookupTool {

    private final KbRetriever retriever;
    private final PlaceDirectory places;
    private final MapOptions options;

    public KbLookupTool(KbRetriever retriever, PlaceDirectory places, MapOptions options) {
        this.retriever = retriever;
        this.places = places;
        this.options = options;
    }

    /** 工具方法名即工具名：{@code lookup} —— 授权白名单里用的就是它 */
    @Tool("查询《雾锁王国》知识库，返回最相关的资料片段。只读，不会改动任何数据。")
    public String lookup(@P("要查的词或问题") String query) {
        if (query == null || query.isBlank()) {
            return "没有提供要查的内容。";
        }
        KbRetriever.Retrieval r = retriever.retrieve(query);
        if (r.hits().isEmpty()) {
            return "知识库里没有查到与「" + query + "」相关的内容。";
        }
        StringBuilder sb = new StringBuilder();
        for (KbRetriever.Hit h : r.hits()) {
            sb.append("【").append(h.entry().title()).append("】\n")
                    .append(h.entry().text()).append("\n\n");
        }
        return sb.toString().strip();
    }

    /**
     * 查地图上某个地点/资源在哪。
     *
     * <p>问「哪里有铁矿」时应该调它，而不是调 {@link #lookup} ——
     * 后者给的是文本片段，回答不了"在哪"。
     */
    @Tool("查询《雾锁王国》地图上某类地点或资源的位置，返回它相对最近地标的方位、距离与坐标。"
            + "问『哪里有X』『X在哪』时用这个。只读。")
    public String locate(@P("要查的地点或资源名，例如 铁矿、远古尖塔、木匠") String place) {
        if (place == null || place.isBlank()) {
            return "没有提供要查的地点。";
        }
        if (!places.isAvailable()) {
            return "地图点位数据暂时不可用。";
        }
        PlaceAnswer.Answer answer = PlaceAnswer.answer(place.strip(), places,
                options.getAnswerLimit(), options.getUnitsPerMeter());
        return answer.text(options.getUnitsPerMeter());
    }
}
