package com.example.qqbot.kb.proposal;

import com.example.qqbot.llm.LlmRouter;
import com.example.qqbot.persistence.KbProposalRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 知识库改进提案。
 *
 * <p><b>要解决的问题</b>：`verdict` 字段和标注功能早就有，但生产库里 1,279 行
 * **全是 `unknown`** —— 缺的不是字段，是「让标记变便宜 + 标记之后真的有用」这条闭环。
 *
 * <p><b>闭环</b>：
 * <ol>
 *   <li>管理员在「问答记录」里给回答打「有帮助 / 没帮助」（一次点击）</li>
 *   <li>累计到阈值（默认 10 条「有帮助」）后，可以点「让 agent 分析」</li>
 *   <li>agent 拿这些<b>被人工认可</b>的回答，跟知识库里对应词条的现有文本比，
 *       产出提案：<b>新增</b>（库里没有）或 <b>覆盖</b>（库里的过时/不全）</li>
 *   <li>提案进队列，<b>人工确认后</b>才写进知识库 —— 不自动落库</li>
 * </ol>
 *
 * <p><b>为什么不自动落库</b>：模型幻觉会静默污染知识库；而且改动已存在块的文本
 * 会触发索引更新，一旦写坏，机器人的回答会跟着一起错，还很难回溯是哪次改的。
 *
 * <p><b>两条写入路径都复用现有机制，不另造一套</b>：
 * <ul>
 *   <li>{@code overwrite} → 走 {@link KbTermService#updateChunkText} 的单块重嵌：
 *       只重算这一块的向量、原地改索引，1 次 embedding 请求，行号不变</li>
 *   <li>{@code new} → 走 {@link KbBlockAdminService#add} 直接落地
 *       （2026-09-29 起不再绕「投递 → 审核 → 建索引」）</li>
 * </ul>
 *
 * <h2>本类只剩「语义」</h2>
 * SQL 与 {@code java.sql} 全部搬进了 {@link KbProposalRepository}。
 * 这里管的是：阈值判定、提示词、模型回复的解析与**纠错**（{@link #extractJson}）、
 * 以及「先写知识库、再改提案状态」这个顺序。
 */
@Service
public class KbProposalService {

    private static final Logger log = LoggerFactory.getLogger(KbProposalService.class);

    /** 一条提案 */
    public record Proposal(long id, String createdAt, String status, String kind,
                           String title, String question, String currentText,
                           String proposedText, String reason, long sourceStatId,
                           String reviewedAt, String reviewedBy) {
    }

    /** 分析的汇总结果 */
    public record AnalyzeResult(int goodTotal, int analyzed, int proposals, String message) {
    }

    /** 数据访问全部委托给它 —— SQL 与 java.sql 都在 persistence */
    private final KbProposalRepository repo;
    /** 当前语料 —— 见 {@link com.example.qqbot.kb.KbCorpus}（现在只有块表一个实现） */
    private final com.example.qqbot.kb.KbCorpus corpus;
    /** 提案落地要按块 id 写（会自动重算那一块的向量） */
    private final com.example.qqbot.kb.block.KbBlockAdminService blockAdmin;

    private final LlmRouter router;
    private final ObjectMapper mapper;


    /** 触发一次分析所需的「有帮助」条数 */
    @Value("${app.kb.proposal.threshold:10}")
    private int threshold;

    private volatile boolean available;

    public KbProposalService(KbProposalRepository repo, com.example.qqbot.kb.KbCorpus corpus,
                             LlmRouter router,
                             ObjectMapper mapper,
                             com.example.qqbot.kb.block.KbBlockAdminService blockAdmin) {
        this.repo = repo;
        this.corpus = corpus;
        this.blockAdmin = blockAdmin;
        this.router = router;
        this.mapper = mapper;
    }

    @PostConstruct
    void init() {
        try {
            if (!repo.isAvailable()) {
                log.warn("[PROPOSAL] 问答库不可用，提案功能关闭");
                return;
            }
            repo.initSchema();
            available = true;
            log.info("[PROPOSAL] 提案库就绪：阈值 {} 条「有帮助」，当前待审 {} 条",
                    threshold, count("pending"));
        } catch (Exception e) {
            log.warn("[PROPOSAL] 初始化失败（不影响问答）：{}", e.getMessage());
            available = false;
        }
    }

    public boolean isAvailable() {
        return available;
    }

    // ==================== 读 ====================

    /** 还没被分析过的「有帮助」回答有几条 —— 前端用它显示"还差几条到阈值" */
    public int pendingGoodCount() {
        if (!available) {
            return 0;
        }
        try {
            return repo.pendingGoodCount();
        } catch (Exception e) {
            return 0;
        }
    }

    public Map<String, Object> stats() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("threshold", threshold);
        out.put("pendingGood", pendingGoodCount());
        out.put("canAnalyze", pendingGoodCount() >= threshold);
        out.put("pending", count("pending"));
        out.put("approved", count("approved"));
        out.put("rejected", count("rejected"));
        out.put("available", available);
        return out;
    }

    public List<Proposal> list(String status, int limit) {
        List<Proposal> out = new ArrayList<>();
        if (!available) {
            return out;
        }
        try {
            for (KbProposalRepository.Row r : repo.list(status, limit)) {
                out.add(toProposal(r));
            }
        } catch (Exception e) {
            log.warn("[PROPOSAL] 读取提案失败：{}", e.getMessage());
        }
        return out;
    }

    private int count(String status) {
        if (!available) {
            return 0;
        }
        try {
            return repo.countByStatus(status);
        } catch (Exception e) {
            return 0;
        }
    }

    private static Proposal toProposal(KbProposalRepository.Row r) {
        return new Proposal(r.id(), r.createdAt(), r.status(), r.kind(), r.title(), r.question(),
                r.currentText(), r.proposedText(), r.reason(), r.sourceStatId(),
                r.reviewedAt(), r.reviewedBy());
    }

    // ==================== 分析（agent）====================

    /**
     * 让模型拿「被人工认可的回答」跟知识库现有文本比对，产出提案。
     *
     * <p>目标词条<b>不由模型决定</b>：取这次问答检索到的最高分块标题。
     * 那是有检索依据的，比让模型猜一个标题可靠得多 —— 模型只负责判断
     * 「现有文本够不够、要不要覆盖」以及写新文本。
     */
    public AnalyzeResult analyze(int maxItems) {
        if (!available) {
            return new AnalyzeResult(0, 0, 0, "提案库不可用");
        }
        if (!router.isAvailable()) {
            return new AnalyzeResult(0, 0, 0, "模型不可用，无法分析");
        }
        // 阈值对着【待分析总数】判断，而不是本次取到的行数 ——
        // 否则 max 一小就永远够不到阈值。（阈值管"能不能开跑"，
        // max 只管"这一轮最多花几次模型调用"。）
        int pending = pendingGoodCount();
        if (pending < threshold) {
            return new AnalyzeResult(pending, 0, 0,
                    "「有帮助」的回答只有 " + pending + " 条，还不够 " + threshold + " 条");
        }
        List<KbProposalRepository.GoodAnswer> rows;
        try {
            rows = repo.pendingGoodAnswers(maxItems);
        } catch (Exception e) {
            return new AnalyzeResult(0, 0, 0, "读取已认可回答失败：" + e.getMessage());
        }
        int made = 0;
        int done = 0;
        for (KbProposalRepository.GoodAnswer row : rows) {
            long statId = row.statId();
            String question = str(row.question());
            String answer = str(row.answer());
            if (question.isEmpty() || answer.isEmpty()) {
                continue;
            }
            String title = topTitle(str(row.retrieved()));
            String current = title.isEmpty() ? "" : currentText(title);
            try {
                JsonNode verdict = askModel(question, answer, title, current);
                done++;
                String kind = verdict.path("kind").asText("").trim();
                String text = verdict.path("text").asText("").trim();
                if ("none".equals(kind)) {
                    // 模型认为知识库已经够了 —— 这是正常结果，不是错误
                    log.info("[PROPOSAL] #{} 模型认为「{}」现有文本已足够，不提案",
                            statId, title.isEmpty() ? "（未命中词条）" : title);
                    continue;
                }
                if (!"new".equals(kind) && !"overwrite".equals(kind)) {
                    // kind 既不是 none 也不是 new/overwrite —— 模型答非所问，要能被看见
                    log.warn("[PROPOSAL] #{} 模型返回了无法识别的 kind={}，跳过（回复：{}）",
                            statId, kind, shortOf(verdict.toString()));
                    continue;
                }
                if (text.isEmpty()) {
                    continue;
                }
                String useTitle = verdict.path("title").asText(title).trim();
                if (useTitle.isEmpty()) {
                    continue;
                }
                repo.insertIgnore(new KbProposalRepository.NewProposal(kind, useTitle, question,
                        current, text, verdict.path("reason").asText(""), statId),
                        Instant.now().toString());
                made++;
            } catch (Exception e) {
                log.warn("[PROPOSAL] 分析 {} 失败：{}", statId, e.getMessage());
            }
        }
        String msg = "分析了 " + done + " 条被认可的回答，产出 " + made + " 条提案（待你确认）";
        return new AnalyzeResult(rows.size(), done, made, msg);
    }

    /** 从 retrieved JSON 里取最高分块的标题 —— 这就是这次问答实际命中的词条 */
    private String topTitle(String retrievedJson) {
        if (retrievedJson == null || retrievedJson.isBlank()) {
            return "";
        }
        try {
            JsonNode arr = mapper.readTree(retrievedJson);
            if (arr.isArray() && !arr.isEmpty()) {
                return arr.get(0).path("title").asText("");
            }
        } catch (Exception ignored) {
            // 老记录可能不是 JSON，忽略
        }
        return "";
    }

    private String currentText(String title) {
        StringBuilder sb = new StringBuilder();
        for (com.example.qqbot.kb.KbCorpus.Entry e : corpus.entries()) {
            if (e.title().equals(title)) {
                sb.append(e.text()).append('\n');
            }
        }
        return sb.toString().trim();
    }

    private JsonNode askModel(String question, String answer, String title, String current) {
        String prompt = """
                你在维护一个《雾锁王国》问答机器人的知识库。下面是**已被人工确认有帮助**的一次问答，
                以及知识库里对应词条的现有文本。请判断现有文本是否需要改进。

                问题：%s
                这次被认可的回答：%s
                知识库现有词条：%s
                现有文本：%s

                只输出 JSON，不要任何其它文字：
                {"kind":"new|overwrite|none","title":"词条英文名","text":"要写入知识库的完整文本","reason":"一句话理由"}
                规则：
                - 现有文本已经覆盖了这次回答的内容 → kind 用 none
                - 词条不存在（现有文本为空）→ kind 用 new
                - 词条存在但内容缺失/过时 → kind 用 overwrite
                - text 要是**可直接替换进知识库的完整文本**，不要写"建议补充…"这种说明
                - 不要编造游戏里不存在的内容；把握不准就用 none
                """.formatted(question, answer,
                title.isEmpty() ? "（这次没有命中任何词条）" : title,
                current.isEmpty() ? "（无）" : current);
        String reply = router.chat(prompt);
        String json = extractJson(reply);
        if (json == null) {
            throw new IllegalStateException("模型回复里没有 JSON："
                    + shortOf(reply));
        }
        try {
            return mapper.readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException("模型返回的 JSON 解析失败："
                    + shortOf(json));
        }
    }

    /**
     * 模型常把 JSON 包在 ```json 里，或者前后带话 —— 抠出第一个花括号块。
     *
     * <p>⚠️ 抠不到时返回 {@code null}，<b>不能返回 "{}"</b>：空对象解析后
     * {@code kind} 会取默认值 {@code none}，于是「模型答非所问」和
     * 「模型认为不用改」变成同一种结果 —— 功能会一直不产出提案却不报错。
     */
    private static String extractJson(String s) {
        if (s == null) {
            return null;
        }
        int a = s.indexOf('{');
        int b = s.lastIndexOf('}');
        return (a >= 0 && b > a) ? s.substring(a, b + 1) : null;
    }

    private static String shortOf(String s) {
        if (s == null) {
            return "";
        }
        String t = s.strip().replaceAll("\\s+", " ");
        return t.length() > 120 ? t.substring(0, 120) + "…" : t;
    }

    // ==================== 审 ====================

    /**
     * 人工确认/拒绝。
     *
     * <p><b>顺序不能反</b>：先写知识库、成功了才把提案标记成 approved。
     * 反过来的话，知识库写失败时提案已经是 approved —— 人工不会再点第二次，
     * 这条改进就永远丢了。
     *
     * @param approve true = 写进知识库；false = 只标记拒绝，不动知识库
     * @return 结果说明（失败时作为错误信息回给前端）
     */
    public String review(long id, boolean approve, String by) {
        if (!available) {
            return "提案库不可用";
        }
        Proposal p = byId(id);
        if (p == null) {
            return "没有这条提案";
        }
        if (!"pending".equals(p.status())) {
            return "这条提案已经处理过了（" + p.status() + "）";
        }
        if (approve) {
            try {
                if ("overwrite".equals(p.kind())) {
                    // 找到该词条的第一块，原地覆盖 —— 只重算这一块的向量
                    com.example.qqbot.kb.KbCorpus.Entry target = null;
                    for (com.example.qqbot.kb.KbCorpus.Entry e : corpus.entries()) {
                        if (e.title().equals(p.title())) {
                            target = e;
                            break;
                        }
                    }
                    if (target == null) {
                        return "知识库里没有词条「" + p.title() + "」，无法覆盖；请改成新增";
                    }
                    // 按**块 id** 写（走管理服务，会自动重算这一块的向量）
                    blockAdmin.updateText(target.id(), p.proposedText());
                } else {
                    // 新增：直接写进块表。
                    // （2026-09-29 起不做"投递"了 —— 资料一律以符合格式的文档导入，
                    //   所以这里不再绕"投递 → 审核 → 建索引"，直接落地。）
                    // docId 用 proposal-<提案号>（英文，合法 id），块序自动补；
                    // 标签归到「大佬攻略」：提案来自群友认可的回答，本质就是实战心得。
                    blockAdmin.add("proposal-" + id, null, p.title(), p.proposedText(),
                            "", java.util.List.of(
                                    com.example.qqbot.kb.category.KbGroups.CONTRIB_TAG));
                }
            } catch (Exception e) {
                log.warn("[PROPOSAL] 落地提案 {} 失败：{}", id, e.getMessage());
                return "写进知识库失败：" + e.getMessage();
            }
        }
        try {
            repo.markReviewed(id, approve ? "approved" : "rejected",
                    Instant.now().toString(), by == null ? "human" : by);
        } catch (Exception e) {
            return "更新提案状态失败：" + e.getMessage();
        }
        return approve
                ? ("overwrite".equals(p.kind())
                    ? "已覆盖词条「" + p.title() + "」（只重算了这一块的向量）"
                    : "已作为新资料投递，去「资料」页审核并建索引")
                : "已拒绝，知识库未改动";
    }

    private Proposal byId(long id) {
        try {
            KbProposalRepository.Row r = repo.findById(id);
            return r == null ? null : toProposal(r);
        } catch (Exception ignored) {
            // 当作不存在
        }
        return null;
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o).trim();
    }
}
