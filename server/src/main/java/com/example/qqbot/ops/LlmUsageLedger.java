package com.example.qqbot.ops;

import com.example.qqbot.time.AppTime;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * token 用量的按日账本 —— 进程内先攒，再由 {@link MetricsRollupJob} 每分钟落库。
 *
 * <h2>为什么不在调用线程里直接写库</h2>
 * 群聊每一轮工具调用都是一次模型往返，直接在回复路径上写 SQLite，
 * 就会和知识库导入、聊天落库抢同一把全局锁 —— 那是把"记账"的延迟
 * 转嫁给了等回答的人。攒在内存里、由现有的每分钟任务统一写，
 * 代价是进程崩溃时最多丢一分钟的账，这和现有的流量/调用指标口径一致。
 *
 * <h2>为什么要按 provider / model / estimated 分桶</h2>
 * 只记一个总数回答不了"钱花在哪个模型上"；把估算值和厂商回报值混在一行，
 * 又会让页面上的"约"字失去依据。分桶之后，真实值与估算值都能单独展示。
 */
@Component
public class LlmUsageLedger {

    /** 一条待落库的日账：同一天、同一模型、同一种来源合成一行 */
    public record Bucket(String day, String provider, String model, boolean estimated,
                         long calls, long tokensIn, long tokensOut) {
    }

    private record Key(String day, String provider, String model, boolean estimated) {
    }

    /** day / provider / model / estimated -> {calls, tokensIn, tokensOut} */
    private final Map<Key, long[]> buckets = new HashMap<>();

    /**
     * 记一次成功的模型往返。
     *
     * <p>用锁而不是 {@link java.util.concurrent.ConcurrentHashMap} 的 {@code compute}：
     * {@link #drain()} 必须"取快照 + 清空"是一个原子动作，否则快照之后、
     * 清空之前到达的一次调用会被无声抹掉。锁的粒度是一次内存加法，
     * 不碰 IO，不会把模型调用拖慢。
     *
     * @param estimated true = 厂商没回 usage，数字来自 {@code TokenEstimator}
     */
    public synchronized void record(String provider, String model,
                                    long tokensIn, long tokensOut, boolean estimated) {
        String p = provider == null ? "" : provider.trim();
        if (p.isEmpty()) {
            // 名字都没有的调用无法归属，记了只会让页面多一行空白
            return;
        }
        String m = model == null ? "" : model.trim();
        Key key = new Key(LocalDate.now(AppTime.ZONE).toString(), p, m, estimated);
        long[] value = buckets.computeIfAbsent(key, k -> new long[3]);
        value[0]++;
        value[1] += Math.max(0L, tokensIn);
        value[2] += Math.max(0L, tokensOut);
    }

    /** 取走并清空；落库任务用 */
    public synchronized List<Bucket> drain() {
        List<Bucket> out = snapshot();
        buckets.clear();
        return out;
    }

    /**
     * 看一眼但不清空 —— 管理端要能读到"还没落库的这一分钟"，
     * 否则页面上的今日总量总会比实际少一截。
     */
    public synchronized List<Bucket> pending() {
        return snapshot();
    }

    private List<Bucket> snapshot() {
        List<Bucket> out = new ArrayList<>(buckets.size());
        buckets.forEach((key, value) -> out.add(new Bucket(
                key.day(), key.provider(), key.model(), key.estimated(),
                value[0], value[1], value[2])));
        return out;
    }
}
