package com.example.qqbot.plaza;

import com.example.qqbot.config.PlazaProperties;
import com.example.qqbot.publicapi.PublicRateLimiter;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 问答广场的公开接口（无需登录）。
 *
 * <p>三类接口：
 * <ol>
 *   <li>浏览：关键词列表 + 某关键词的答案 —— 只读、不含任何隐私字段</li>
 *   <li>投票：赞 / 踩 / 已过时</li>
 *   <li>降级：问问新答案 / 求助大佬 —— 有严格限额</li>
 * </ol>
 *
 * <p>身份怎么来：公开站不要求登录。前端在 localStorage 存一个随机 clientId。
 * 这只是「防误刷」，不是强身份 —— 真要刷还是能刷（清缓存）。
 * 但投票本身影响不大（票数低的内容自然不会展示），所以够用。
 */
@RestController
@RequestMapping("/api/public/plaza")
public class PlazaController {

    private static final Logger log = LoggerFactory.getLogger(PlazaController.class);

    private final AnswerAggregator aggregator;
    private final PlazaStore store;
    private final PlazaProperties props;
    private final PublicRateLimiter limiter;
    private final FallbackService fallback;

    public PlazaController(AnswerAggregator aggregator, PlazaStore store,
                           PlazaProperties props, PublicRateLimiter limiter,
                           FallbackService fallback) {
        this.aggregator = aggregator;
        this.store = store;
        this.props = props;
        this.limiter = limiter;
        this.fallback = fallback;
    }

    // ==================== 浏览 ====================

    /** 大家都在查什么（公开站首页用） */
    @GetMapping("/keywords")
    public ResponseEntity<?> keywords(@RequestParam(defaultValue = "30") int limit,
                                      HttpServletRequest request) {
        ResponseEntity<?> denied = rateLimit(request);
        if (denied != null) {
            return denied;
        }
        List<Map<String, Object>> all = aggregator.hotKeywords(Math.min(limit * 4, 240));

        // C 方案：两层回退
        //   1) 优先「被点赞认可」的 —— 社区筛选过，质量最高
        //   2) 不够就用「被问最多」的补齐 —— 冷启动时避免整个板块空着
        List<Map<String, Object>> voted = new ArrayList<>();
        List<Map<String, Object>> asked = new ArrayList<>();
        for (Map<String, Object> m : all) {
            long v = ((Number) m.getOrDefault("votedCount", 0)).longValue();
            long c = ((Number) m.getOrDefault("count", 0)).longValue();
            if (v > 0) {
                voted.add(m);
            } else if (c > 0) {
                asked.add(m);
            }
        }
        voted.sort((a, b) -> Long.compare(
                ((Number) b.getOrDefault("votedCount", 0)).longValue(),
                ((Number) a.getOrDefault("votedCount", 0)).longValue()));

        List<Map<String, Object>> out = new ArrayList<>(
                voted.size() > limit ? voted.subList(0, limit) : voted);
        boolean mixed = false;
        if (out.size() < limit) {
            mixed = true;
            for (Map<String, Object> m : asked) {
                if (out.size() >= limit) {
                    break;
                }
                out.add(m);
            }
        }

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("keywords", out);
        resp.put("count", out.size());
        // 前端据此决定提示语：全是投票的，还是掺了「被问最多」的
        resp.put("votedCount", voted.size());
        resp.put("mixed", mixed);
        return ResponseEntity.ok(resp);
    }

    /** 某关键词的答案页 */
    @GetMapping("/keyword")
    public ResponseEntity<?> keywordPage(@RequestParam String keyword,
                                         HttpServletRequest request) {
        ResponseEntity<?> denied = rateLimit(request);
        if (denied != null) {
            return denied;
        }
        if (keyword == null || keyword.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "需要 keyword"));
        }
        AnswerAggregator.KeywordPage page = aggregator.page(keyword.trim());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("keyword", page.keyword());
        out.put("termEn", page.termEn());
        out.put("askedCount", page.askedCount());
        out.put("needsNewAnswer", page.needsNewAnswer());
        out.put("needsHelp", page.needsHelp());
        out.put("onlyVoted", props.isOnlyVoted());

        List<Map<String, Object>> answers = page.answers().stream().map(a -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("statId", a.statId());
            m.put("question", a.question());
            m.put("answer", a.answer());
            m.put("up", a.up());
            m.put("down", a.down());
            m.put("outdated", a.outdated());
            m.put("badge", a.badge());
            return m;
        }).toList();
        out.put("answers", answers);
        return ResponseEntity.ok(out);
    }

    // ==================== 投票 ====================

    /**
     * 投票。
     *
     * <p>注意：不接收 QQ 号 —— 只收前端生成的 clientId（随机串）。
     * 服务端把它哈希后存库，既防重复投票又不关联到人。
     */
    @PostMapping("/vote")
    public ResponseEntity<?> vote(@RequestBody Map<String, Object> body,
                                  HttpServletRequest request) {
        ResponseEntity<?> denied = rateLimit(request);
        if (denied != null) {
            return denied;
        }
        if (!props.isEnabled() || !store.isAvailable()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("error", "问答广场未启用"));
        }

        long statId = asLong(body.get("statId"));
        String voteType = asString(body.get("vote"));
        String clientId = asString(body.get("clientId"));

        if (statId <= 0) {
            return ResponseEntity.badRequest().body(Map.of("error", "需要 statId"));
        }
        if (!PlazaStore.isValidVote(voteType)) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "vote 只能是 up / down / outdated"));
        }
        if (clientId == null || clientId.length() < 8) {
            return ResponseEntity.badRequest().body(Map.of("error", "缺少 clientId"));
        }

        long pseudoId = Math.abs(clientId.hashCode());
        boolean ok = store.vote(statId, pseudoId, voteType);
        if (!ok) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "投票失败"));
        }

        var summary = store.voteSummaries(List.of(statId)).get(statId);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("myVote", voteType);
        if (summary != null) {
            out.put("up", summary.up());
            out.put("down", summary.down());
            out.put("outdated", summary.outdated());
        }
        return ResponseEntity.ok(out);
    }

    // ==================== 降级：问问新答案 / 求助大佬 ====================

    /**
     * 第 2 级降级：让机器人重新生成一条回答。
     *
     * <p>会调用大模型，所以有严格限额（每关键词每天 N 次 + 全局每天 M 次）。
     */
    @PostMapping("/ask-new")
    public ResponseEntity<?> askNew(@RequestBody Map<String, Object> body,
                                    HttpServletRequest request) {
        ResponseEntity<?> denied = rateLimit(request);
        if (denied != null) {
            return denied;
        }
        if (!props.isEnabled()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("error", "问答广场未启用"));
        }
        String keyword = asString(body.get("keyword"));
        String question = asString(body.get("question"));
        Map<String, Object> result = fallback.askNew(keyword, question);
        return Boolean.TRUE.equals(result.get("ok"))
                ? ResponseEntity.ok(result)
                : ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(result);
    }

    /**
     * 第 3 级降级：生成一条「求助链接」。
     *
     * <p>⚠️ <b>这里刻意不直接发消息</b>。原因：
     * <ul>
     *   <li>网页访客无法证明自己属于某个群 —— 若能直接指定群号，
     *       就成了「向任意群广播」的漏洞</li>
     *   <li>群号也不该由网页填写（那是猜的）</li>
     * </ul>
     *
     * <p>所以改成：网页生成一个**待办求助**，附上短码；
     * 用户把短码（或直接复制那句话）发到群里 @ 机器人，
     * 由群聊上下文里的真实群号来决定发到哪。
     */
    @PostMapping("/help-request")
    public ResponseEntity<?> helpRequest(@RequestBody Map<String, Object> body,
                                         HttpServletRequest request) {
        ResponseEntity<?> denied = rateLimit(request);
        if (denied != null) {
            return denied;
        }
        if (!props.isEnabled()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("error", "问答广场未启用"));
        }
        String keyword = asString(body.get("keyword"));
        String question = asString(body.get("question"));
        if (question == null || question.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "需要 question"));
        }

        String code = store.createHelpRequest(keyword, question);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("code", code);
        out.put("text", "求助：" + question.trim());
        out.put("howto", "把下面这句话连同 @机器人 一起发到群里，我就会在群里问大家：");
        out.put("note", "这样能确保只发到你所在的群 —— 别人填群号是发不出去的");
        return ResponseEntity.ok(out);
    }
    // ==================== 内部 ====================

    private ResponseEntity<?> rateLimit(HttpServletRequest request) {
        String ip = clientIp(request);
        if (!limiter.allow(ip)) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .header("Retry-After", "60")
                    .body(Map.of("error", "请求太频繁，请稍后再试"));
        }
        return null;
    }

    private String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            int comma = forwarded.indexOf(',');
            return (comma > 0 ? forwarded.substring(0, comma) : forwarded).trim();
        }
        return request.getRemoteAddr();
    }

    private static long asLong(Object o) {
        if (o instanceof Number n) {
            return n.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(o));
        } catch (Exception e) {
            return 0;
        }
    }

    private static String asString(Object o) {
        if (o == null) {
            return null;
        }
        String s = String.valueOf(o).trim();
        return s.isEmpty() ? null : s;
    }
}
