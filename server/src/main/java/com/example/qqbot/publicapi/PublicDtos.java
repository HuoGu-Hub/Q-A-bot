package com.example.qqbot.publicapi;

import java.util.List;

/**
 * 公开接口的数据结构（DTO）。
 *
 * <p><b>这些 record 是安全边界的一部分</b>：它们**刻意只包含可公开的字段**。
 * 管理侧的 {@code QaAnalytics.RecordRow} 有 groupId / userId / bestCosine，
 * 这里一个都没有 —— 从类型层面就不可能泄露出去。
 *
 * <p>所以公开接口**绝不复用管理接口的返回值**，即使字段"看起来差不多"。
 */
public final class PublicDtos {

    private PublicDtos() {
    }

    /**
     * 公开统计。
     *
     * <p>刻意**不含**用户数、群数、延迟分位数 —— 那些是运营指标，
     * 公开出去只会让人推断出群规模和活跃度。
     *
     * @param questions 提问量 —— 定义：@ 了机器人 + 机器人有效回应且未被拦截
     *                  （{@code guard_action='pass'}）。
     *                  ⚠️ 原来这个字段叫 {@code totalQuestions}，但取的是
     *                  {@code overview().total()}，即<b>全部群消息</b>（含群里
     *                  没 @ 机器人的闲聊）—— 对外宣称的提问数因此错了约 17 倍。
     */
    public record PublicStats(
            long questions,
            double hitRate,
            long kbEntries,
            long glossaryTerms,
            String since) {
    }

    /** 一条知识库条目 —— 内容来自公开 wiki，无隐私问题 */
    public record KbEntry(
            String title,
            String url,
            String text,
            List<String> cats) {
    }

    /**
     * 检索结果。
     *
     * @param mode semantic = 走向量路（能理解中文口语提问）；keyword = 只走了本地关键词路
     *             （术语表命中，零外部成本）。前端据此提示"语义搜索"
     */
    public record KbSearchResult(String query, int count, String mode, List<KbEntry> entries) {
    }

    public record KbCategory(String name, long count) {
    }
}
