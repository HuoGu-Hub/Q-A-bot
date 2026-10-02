package com.example.qqbot.plaza;

import com.example.qqbot.persistence.PlazaQueryRepository;

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
 * 问答广场的管理接口（管理后台专用，需要登录）。
 *
 * <p>和公开接口的区别：
 * <ul>
 *   <li>能看到未脱敏的原文（含 QQ 号、群号）—— 只有管理员能看</li>
 *   <li>能看到投票明细（谁投的、什么时候）</li>
 *   <li>能下架不当内容</li>
 *   <li>能看到求助记录</li>
 * </ul>
 */
@RestController
@RequestMapping("/admin/api/plaza")
public class PlazaAdminController {

    private final PlazaStore store;
    private final PlazaPolicy props;
    private final FallbackService fallback;
    /** 跨表读全部委托给它 —— 本类不再碰 java.sql */
    private final PlazaQueryRepository queries;

    public PlazaAdminController(PlazaStore store, PlazaPolicy props,
                                FallbackService fallback,
                                PlazaQueryRepository queries) {
        this.store = store;
        this.props = props;
        this.fallback = fallback;
        this.queries = queries;
    }

    /** 概览 */
    @GetMapping("/overview")
    public Map<String, Object> overview() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("enabled", props.isEnabled());
        out.put("onlyVoted", props.isOnlyVoted());
        out.put("voteCount", store.voteCount());
        out.put("helpCount", store.helpCount());
        out.put("usage", fallback.usage());
        out.put("downvoteThreshold", props.getDownvoteThreshold());
        return out;
    }

    /**
     * 被投票的内容（管理后台主视图）。
     *
     * <p>展示所有被投过票的回答，含未达到「上架」门槛的 ——
     * 这样你能看到「有人在踩但还没上架」的东西，提前发现问题。
     */
    @GetMapping("/answers")
    public Map<String, Object> answers(@RequestParam(defaultValue = "50") int limit) {
        List<Map<String, Object>> rows = new ArrayList<>();
        try {
            for (PlazaQueryRepository.VotedAnswer a : queries.votedAnswers(limit)) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("statId", a.statId());
                m.put("ts", a.ts());
                m.put("groupId", a.groupId());
                m.put("userId", a.userId());
                m.put("source", a.source());
                m.put("question", a.question());
                m.put("answer", a.answer());
                m.put("up", a.up());
                m.put("down", a.down());
                m.put("outdated", a.outdated());
                rows.add(m);
            }
        } catch (Exception e) {
            // 表还没建时返回空列表即可
        }
        return Map.of("answers", rows, "count", rows.size());
    }

    /** 某条答案的投票明细（谁投的） */
    @GetMapping("/votes")
    public Map<String, Object> votes(@RequestParam long statId) {
        return Map.of("statId", statId, "votes", store.voteDetails(statId));
    }

    /**
     * 下架一条内容。
     *
     * <p>做法不是删除，而是清空投票 —— 清空后它就达不到「被点赞」门槛，
     * 自然从公开站消失。保留原文便于事后复查。
     */
    @PostMapping("/takedown")
    public ResponseEntity<Map<String, Object>> takedown(@RequestBody Map<String, Object> body) {
        long statId = asLong(body.get("statId"));
        if (statId <= 0) {
            return ResponseEntity.badRequest().body(Map.of("error", "需要 statId"));
        }
        boolean ok = store.takedown(statId);
        return ok
                ? ResponseEntity.ok(Map.of("ok", true,
                        "note", "已下架 —— 投票已清空，该内容不再出现在公开站"))
                : ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                        .body(Map.of("error", "下架失败"));
    }

    /** 求助记录 */
    @GetMapping("/help")
    public Map<String, Object> help(@RequestParam(defaultValue = "50") int limit) {
        List<Map<String, Object>> rows = store.recentHelp(Math.min(limit, 200));
        return Map.of("requests", rows, "count", rows.size());
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
}
