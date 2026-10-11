package com.example.qqbot.kb.term;

import com.example.qqbot.kb.KnowledgeChangedEvent;
import com.example.qqbot.kb.NameCandidate;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 名字层服务：改词条的中文名与核对状态。
 *
 * <h2>为什么只剩这么点</h2>
 * 目录读模型、筛选口径、导出格式原先都挤在这里，于是"改一个列宽"要在几百行里找。
 * 它们现在各归其位：目录与导出去组合层 {@code kb.catalog}，
 * 筛选口径在 {@code KbCatalogViews}，内容与向量在 {@code kb.block}。
 * 本类只做一件事 —— 写名字。
 *
 * <p><b>为什么写名字必须便宜</b>：它是"补术语表零成本"的技术依据。
 * 单行 UPDATE，不碰语料、不调 embedding、不重建索引。
 * 内容与向量必须成对更新（只改一边会静默用错资料），
 * 而改名字根本不进那条路径 —— 这是两者分成两个真源的理由。
 */
@Service
public class KbTermService {

    private final KbTermStore termStore;

    /**
     * 发布"知识库变了"的事件。
     *
     * <p>**不直接调公开站** —— 那是层次倒置（知识库不该知道公开站有检索缓存）。
     * 谁关心谁自己订阅，见 {@link KnowledgeChangedEvent}。
     */
    private final ApplicationEventPublisher events;

    public KbTermService(KbTermStore termStore, ApplicationEventPublisher events) {
        this.termStore = termStore;
        this.events = events;
    }

    /**
     * 每次成功写入后调一次 —— 宣布"知识库变了"。
     *
     * <p>以前这里直接调 {@code PublicSearchService.invalidate()}（还得用 ObjectProvider
     * 绕循环依赖）。现在只发事件，公开站自己订阅。
     */
    private void clearSearchCache() {
        events.publishEvent(new KnowledgeChangedEvent("词条变更"));
    }

    /**
     * 改名字 / 状态。
     *
     * <p>不碰语料、不调 embedding、不重建索引 —— 这是「补术语表零成本」的技术依据。
     */
    public boolean saveName(String en, String zh, String status) {
        boolean ok = termStore.upsert(en, zh, status);
        if (ok) {
            clearSearchCache();
        }
        return ok;
    }

    /** 清掉中文名，状态回到 draft。不删行 —— 行代表"这个页面在词条表里有位置" */
    public boolean clearName(String en) {
        boolean ok = termStore.clearName(en);
        if (ok) {
            clearSearchCache();
        }
        return ok;
    }

    /**
     * 登记一批**候选名字** —— 导入文档时调用，由组合层编排。
     *
     * <h2>为什么策略在这一层</h2>
     * 候选是块侧提出的（它只知道块 id 与标题），但"要不要落库、要不要覆盖"是
     * **名字表自己的规矩**：名字可能被人核对或改过，重导一次文档就冲掉等于人工白做。
     * 原先这条规矩写在块侧的导入器里 —— 数据在名字层，策略却在块层，是反的。
     *
     * <p>它同时补上一个原先漏掉的信号：登记成功也发 {@link KnowledgeChangedEvent}。
     * 老路径直接写词条表，版本号变了（检索会重载），但**公开站的搜索缓存不会失效** ——
     * 那种不一致不报错，只表现为"刚导完的页面在公开站搜不到"。
     *
     * @return 真正新建或补齐的条数（已有名字的跳过，不计入）
     */
    public int registerMissing(List<NameCandidate> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return 0;
        }
        int n = 0;
        for (NameCandidate c : candidates) {
            if (c.en() == null || c.en().isBlank() || c.name() == null || c.name().isEmpty()) {
                continue;
            }
            KbTermStore.Entry existing = termStore.get(c.en());
            // 已有名字的不覆盖 —— 名字可能被人核对/改过
            if (existing != null && existing.zh() != null && !existing.zh().isBlank()) {
                continue;
            }
            // 状态沿用已有的（可能已经是 verified），没有则默认 draft
            String status = existing == null || existing.status().isBlank() ? "draft" : existing.status();
            if (termStore.upsert(c.en(), c.name(), status)) {
                n++;
            }
        }
        if (n > 0) {
            clearSearchCache();
        }
        return n;
    }

    /** 批量改核对状态；改完清一次公开站缓存 */
    public KbTermStore.BatchResult setStatusBatch(List<String> ens, String status) {
        KbTermStore.BatchResult r = termStore.setStatusBatch(ens, status);
        if (r.updated() > 0) {
            clearSearchCache();
        }
        return r;
    }
}
