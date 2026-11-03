package com.example.qqbot.llm;

import com.example.qqbot.trace.ToolCallTrace;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.invocation.InvocationContext;
import dev.langchain4j.service.tool.ToolExecutionResult;
import dev.langchain4j.service.tool.ToolExecutor;

/**
 * 工具执行器的包装：把返回长度、耗时与失败原因交给 {@link ToolCallTrace}。
 *
 * <p>为什么必须包执行器而不是继续用授权钩子：授权只发生在调用前，
 * 返回值与耗时只有这里看得到。包装之后 Agent 维度才能回答"工具返回对不对"。
 */
class TracingToolExecutor implements ToolExecutor {

    private final String name;
    private final ToolExecutor delegate;

    TracingToolExecutor(String name, ToolExecutor delegate) {
        this.name = name;
        this.delegate = delegate;
    }

    @Override
    public String execute(ToolExecutionRequest request, Object memoryId) {
        String args = request == null ? "" : request.arguments();
        return ToolCallTrace.run(name, args, () -> delegate.execute(request, memoryId));
    }

    @Override
    public ToolExecutionResult executeWithContext(ToolExecutionRequest request,
                                                  InvocationContext context) {
        String args = request == null ? "" : request.arguments();
        ToolExecutionResult[] holder = new ToolExecutionResult[1];
        ToolCallTrace.run(name, args, () -> {
            holder[0] = delegate.executeWithContext(request, context);
            return holder[0] == null ? "" : holder[0].resultText();
        });
        return holder[0];
    }
}
