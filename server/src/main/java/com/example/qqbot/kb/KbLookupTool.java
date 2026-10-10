package com.example.qqbot.kb;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import org.springframework.stereotype.Component;

/**
 * 只读工具：按关键词查知识库。
 *
 * <p><b>第一批工具只开只读</b>：模型能调工具之后，"群友说一句话"就成了触发面。
 * 改数据、发消息、动配置这类能力一律不放进第一批 —— 先证明授权与循环是可靠的。
 */
@Component
public class KbLookupTool {

    private final KbRetriever retriever;

    public KbLookupTool(KbRetriever retriever) {
        this.retriever = retriever;
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
}
