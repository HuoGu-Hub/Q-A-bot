package com.example.qqbot.kb;

import com.example.qqbot.media.ImageMarkers;
import com.example.qqbot.text.PromptText;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.invocation.InvocationContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 只读工具：查知识库、查条目名、查地图点位。
 *
 * <p><b>第一批工具只开只读</b>：模型能调工具之后，"群友说一句话"就成了触发面。
 * 改数据、发消息、动配置这类能力一律不放进第一批。
 *
 * <h2>为什么返回值里要带相关度与"标题命中"（2026-10-13 补）</h2>
 * 原先只返回标题与正文，模型**没有任何依据判断"这次结果靠不靠谱"** ——
 * 实测「堕落镰刀」那次，返回的全是无关的 Fell 系物品，模型照单全收，
 * 然后按 system.md 说"资料里没查到"。带上分数与命中标记，它才有理由重试。
 *
 * <p>⚠️ 触发重试**不能靠绝对相似度**：实测错的那批重排分高达 0.849，
 * 比很多正常命中还高。所以判据是"**标题一条都没命中**"这种结构性信号。
 */
@Component
public class KbLookupTool {

    private static final Logger log = LoggerFactory.getLogger(KbLookupTool.class);

    private final KbRetriever retriever;
    private final KbCorpus corpus;
    private final PlaceDirectory places;
    private final MapOptions options;
    private final ResourceDirectory resources;

    /** 老签名：没有资源层目录时，locate 只查具名地点（测试与旧调用方用） */
    public KbLookupTool(KbRetriever retriever, KbCorpus corpus, PlaceDirectory places, MapOptions options) {
        this(retriever, corpus, places, options, null);
    }

    /** Spring 用这个：资源层目录可选，生产环境一定有 */
    @Autowired
    public KbLookupTool(KbRetriever retriever, KbCorpus corpus, PlaceDirectory places, MapOptions options,
                        ResourceDirectory resources) {
        this.retriever = retriever;
        this.corpus = corpus;
        this.places = places;
        this.options = options;
        this.resources = resources;
    }

    /** 工具方法名即工具名：{@code lookup} —— 授权白名单里用的就是它 */
    @Tool("查询知识库，返回最相关的资料片段、相关度与是否命中标题。只读，不会改动任何数据。"
            + "知识库正文可能是中文或英文：关键词若结果不佳，请换成资料中的原文再查一次，"
            + "最多重试两次；两次都查不到才说资料里没查到。"
            + "另外：命中资料里若出现了对回答有帮助的材料、地点、技能（例如配方里的铁、布料），"
            + "可以再查它们怎么获得，这样能给出更完整的答案；但要克制，只在明显有用时才查。"
            + "还有：命中资料的正文末尾若出现 [[img:...]] 这样的图片标记，那说明这一块有配图，"
            + "把它原样抄进回答里就会变成一张图；没有标记就不要自己拼。")
    public String lookup(@P("要查的词或问题") String query, InvocationContext ctx) {
        if (query == null || query.isBlank()) {
            return "没有提供要查的内容。";
        }
        // ★ 预算闸门：模型可以多轮检索，但不能无限查下去（时限 + 次数）。
        //   超预算**返回一句提示**而不是抛错 —— 抛错模型看到的是"工具坏了"，
        //   返回提示它才知道该收尾、用现有资料作答。
        ToolBudget budget = ToolBudget.of(ctx);
        if (budget != null && !budget.tryAcquire()) {
            log.info("[TOOL] lookup 被预算拦下：{}（已用 {}/{}，{} ms）", budget.reason(), budget.used(),
                    budget.maxCalls(), budget.elapsedMillis());
            return "本次检索预算已用完（" + budget.reason() + "），请直接用已有资料作答，不要再调用工具。";
        }
        KbRetriever.Retrieval r = retriever.retrieve(query);
        if (r.hits().isEmpty()) {
            return "知识库里没有查到与「" + PromptText.escapeDelimiters(query) + "」相关的内容（关键词候选 " + r.keywordCandidates()
                    + " 条）。如果这是中文词，可以换成资料中的原文再查一次。";
        }
        StringBuilder sb = new StringBuilder();
        int n = 0;
        int titleHits = 0;
        double best = 0;
        for (KbRetriever.Hit h : r.hits()) {
            n++;
            best = Math.max(best, h.score());
            boolean titleHit = titleHit(query, h.entry().title());
            if (titleHit) {
                titleHits++;
            }
            // 正文里的配图标记提到块尾单独一行，模型照抄时不容易漏（与资料注入同一处理）
            sb.append("【").append(PromptText.escapeDelimiters(h.entry().title())).append("】")
                    .append(" 相关度 ").append(String.format("%.3f", h.score()))
                    .append(" 来源 ").append(PromptText.escapeDelimiters(h.source()))
                    .append(titleHit ? " 标题命中" : "")
                    .append("\n").append(PromptText.escapeDelimiters(
                            ImageMarkers.hoistSingleMarker(h.entry().text()))).append("\n\n");
        }
        sb.append("—— 共 ").append(n).append(" 条，最高相关度 ").append(String.format("%.3f", best))
                .append("，标题命中 ").append(titleHits).append(" 条。");
        if (titleHits == 0) {
            sb.append("**这批结果的标题与你的关键词没有一处对上，可能不可靠**：")
                    .append("如果关键词是中文，请换成资料中的原文再查一次；也可以用 search_titles 先确认原文名。");
        }
        log.info("[TOOL] lookup「{}」→ {} 条，最高分 {}，标题命中 {} 条", query, n,
                String.format("%.3f", best), titleHits);
        return sb.toString().strip();
    }

    /**
     * 按关键词查**条目名**（标题）。
     *
     * <p>解决的是"中文名 ↔ 原文名对不上"：模型猜一个关键词，
     * 从返回的标题里认出正确的那个，再用完整原文名去 {@link #lookup}。
     * 这一步是纯字面匹配，不花向量钱，也不受跨语言相似度影响。
     */
    @Tool(name = "search_titles", value = "按关键词查知识库里的条目名（标题），返回匹配到的标题列表。只读。"
            + "当你不知道某个中文名对应的原文名时用它：先猜一个关键词，"
            + "看返回的标题里有没有正确的那个，再用完整原文名去 lookup。")
    public String searchTitles(@P("关键词或关键词片段") String word, InvocationContext ctx) {
        if (word == null || word.isBlank()) {
            return "没有提供关键词。";
        }
        // 与 lookup 同一道闸门 —— 两个工具都能发起检索，都要受限
        ToolBudget budget = ToolBudget.of(ctx);
        if (budget != null && !budget.tryAcquire()) {
            log.info("[TOOL] search_titles 被预算拦下：{}（已用 {}/{}，{} ms）", budget.reason(), budget.used(),
                    budget.maxCalls(), budget.elapsedMillis());
            return "本次检索预算已用完（" + budget.reason() + "），请直接用已有资料作答，不要再调用工具。";
        }
        String w = word.strip().toLowerCase();
        Set<String> titles = new LinkedHashSet<>();
        for (KbCorpus.Entry e : corpus.entries()) {
            String t = e.title();
            if (t == null) {
                continue;
            }
            if (t.toLowerCase().contains(w)) {
                titles.add(t.replaceAll("（第 \\d+ 块）$", "").trim());
            }
            if (titles.size() >= 25) {
                break;
            }
        }
        if (titles.isEmpty()) {
            return "知识库里没有任何条目的名字含「" + PromptText.escapeDelimiters(word)
                    + "」。换个关键词再试，或直接用中文问。";
        }
        String list = titles.stream().map(PromptText::escapeDelimiters)
                .collect(java.util.stream.Collectors.joining("\n- "));
        return "名字含「" + PromptText.escapeDelimiters(word) + "」的条目：\n- " + list;
    }

    /** 查询里的词有没有出现在标题里 —— 比分数可靠得多的"命中"信号 */
    private static boolean titleHit(String query, String title) {
        if (query == null || title == null) {
            return false;
        }
        String t = title.toLowerCase();
        for (String w : query.toLowerCase().split("[^\\p{L}\\p{N}]+")) {
            if (w.length() >= 3 && t.contains(w)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 查地图上某个地点/资源在哪。
     *
     * <p>问「哪里有铁矿」时应该调它，而不是调 {@link #lookup} ——
     * 后者给的是文本片段，回答不了"在哪"。
     */
    @Tool("查询地图上某类地点或资源的位置。地点返回相对最近地标的方位、距离与坐标；"
            + "铜矿、铁矿这类整层资源返回最密的几片点位与坐标。"
            + "问『哪里有X』『X在哪』时用这个，尤其是『哪里有密集的铜矿』这类问题。"
            + "资源结果给的是文字结论，不要拿物品图标代替地图。只读。")
    public String locate(@P("要查的地点或资源名") String place,
                         InvocationContext ctx) {
        if (place == null || place.isBlank()) {
            return "没有提供要查的地点。";
        }
        // ★ 与 lookup / search_titles 同一道闸门（2026-10-15 补）。
        //   locate 原先**不占预算**，于是模型可以反复调它刷轮次，最后撞上
        //   "工具轮次上限"把整条回答连资料一起丢掉 —— 线上实测的兜底话术就是这么来的。
        ToolBudget budget = ToolBudget.of(ctx);
        if (budget != null && !budget.tryAcquire()) {
            log.info("[TOOL] locate 被预算拦下：{}（已用 {}/{}，{} ms）", budget.reason(), budget.used(),
                    budget.maxCalls(), budget.elapsedMillis());
            return "本次检索预算已用完（" + budget.reason() + "），请直接用已有资料作答，不要再调用工具。";
        }
        if (!places.isAvailable()) {
            ResourceDirectory.Answer resource = resourceAnswer(place);
            if (resource != null) {
                return resourceText(resource);
            }
            return "地图点位数据暂时不可用。";
        }
        PlaceAnswer.Answer answer = PlaceAnswer.answer(place.strip(), places,
                options.getAnswerLimit(), options.getUnitsPerMeter());
        if (answer.found()) {
            String text = PromptText.escapeDelimiters(answer.text(options.getUnitsPerMeter()));
            if (!options.getShot().isEnabled()) {
                return text;
            }
            // 附上"取图凭据"：模型不认识坐标，也不该去猜身份 —— 它只需要把这行原样抄进回答。
            // 只给最匹配的那一处：地图图比较大，一次回两张会刷屏。
            return text + "\n配图：[[img:map:" + PromptText.escapeDelimiters(
                    answer.hits().get(0).place().imageKey()) + "]]";
        }
        // 具名地点没有命中：再看是不是在问某层资源的分布（铜矿、铁矿……）
        ResourceDirectory.Answer resource = resourceAnswer(place);
        if (resource != null) {
            return resourceText(resource);
        }
        return PromptText.escapeDelimiters(answer.text(options.getUnitsPerMeter()));
    }

    /** 资源文字 + 第一片热点的取图凭据；截图关闭时只回文字 */
    private String resourceText(ResourceDirectory.Answer resource) {
        String text = PromptText.escapeDelimiters(resource.text());
        if (!options.getShot().isEnabled()) {
            return text;
        }
        return text + "\n配图：[[img:oreshot:" + PromptText.escapeDelimiters(
                ResourceDirectory.imageKey(resource.layer(), 1)) + "]]";
    }

    /**
     * 资源层兜底：具名地点查不到时，看看问的是不是「铜矿 / 铁矿」这类整层资源。
     *
     * <p>资源点没有独立名字，匹配的是"层"（{@code ore:copper}），回答的是"哪一片最密"。
     * 第一阶段只给文字 —— 点云截图需要另一种画法，还没接。
     */
    private ResourceDirectory.Answer resourceAnswer(String place) {
        if (resources == null) {
            return null;
        }
        try {
            ResourceDirectory.Answer answer = resources.answer(place);
            if (answer != null && answer.hotspots() != null && !answer.hotspots().isEmpty()) {
                log.info("[TOOL] locate「{}」→ 资源层 {}：{} 个点，{} 处热点",
                        place.strip(), answer.layer().key(), answer.total(), answer.hotspots().size());
                return answer;
            }
        } catch (Exception e) {
            log.warn("[TOOL] locate 查资源层失败：{} —— {}", place, e.getMessage());
        }
        return null;
    }
}
