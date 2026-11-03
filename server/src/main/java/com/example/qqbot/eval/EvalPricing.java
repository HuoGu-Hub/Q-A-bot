package com.example.qqbot.eval;

import com.example.qqbot.persistence.EvalRepository;
import com.example.qqbot.trace.LlmUsage;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 把 token 换算成钱。
 *
 * <h2>口径</h2>
 * 价目按"每百万 token 多少元"填，成本用**微元**整数存：
 * {@code cost_micro = tokens × price_per_million}。
 * 推导：tokens / 1e6 × price 元 = tokens × price / 1e6 元 = tokens × price 微元。
 * 用整数存是为了让"一批 3000 条累加"不出现浮点尾差。
 *
 * <p>没有价目的模型成本记 0 并标记"未计价" —— 0 元和"免费"是两件事，
 * 页面上不能混着显示，否则成本维度会把"没填价目"算成"很便宜"。
 */
@Component
public class EvalPricing {

    private final EvalRepository repo;

    public EvalPricing(EvalRepository repo) {
        this.repo = repo;
    }

    /** 一次模型往返的成本，单位微元；没有价目返回 null */
    public Long costMicro(LlmUsage.Call call) {
        if (call == null) {
            return null;
        }
        EvalRepository.PriceRow price = repo.findPrice(call.provider(), call.model());
        if (price == null) {
            return null;
        }
        return tokensCost(call.tokensIn(), call.tokensOut(),
                price.inputPerMillion(), price.outputPerMillion());
    }

    /** 一组往返的总成本；任何一次没有价目就返回 null，不拿部分价目冒充总成本 */
    public Long totalCostMicro(List<LlmUsage.Call> calls) {
        if (calls == null || calls.isEmpty()) {
            return null;
        }
        long total = 0;
        for (LlmUsage.Call call : calls) {
            Long one = costMicro(call);
            if (one == null) {
                return null;
            }
            total += one;
        }
        return total;
    }

    static long tokensCost(long tokensIn, long tokensOut, double inputPerMillion,
                           double outputPerMillion) {
        double micro = tokensIn * inputPerMillion + tokensOut * outputPerMillion;
        return Math.max(0, Math.round(micro));
    }
}
