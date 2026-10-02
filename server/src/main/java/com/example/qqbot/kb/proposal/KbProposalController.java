package com.example.qqbot.kb.proposal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 知识库改进提案接口。
 *
 * <p>闭环：问答记录里打「有帮助」→ 累计到阈值 → 让 agent 分析 → <b>人工确认</b> → 写进知识库。
 */
@RestController
@RequestMapping("/admin/api/kb/proposals")
public class KbProposalController {

    private static final Logger log = LoggerFactory.getLogger(KbProposalController.class);

    private final KbProposalService service;

    public KbProposalController(KbProposalService service) {
        this.service = service;
    }

    /** 进度：还差几条到阈值、各状态提案数 */
    @GetMapping("/stats")
    public Map<String, Object> stats() {
        return service.stats();
    }

    @GetMapping("")
    public Map<String, Object> list(@RequestParam(defaultValue = "pending") String status,
                                    @RequestParam(defaultValue = "100") int limit) {
        List<KbProposalService.Proposal> rows = service.list(status, limit);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("rows", rows);
        out.put("status", status);
        return out;
    }

    /**
     * 让 agent 分析被认可的回答，产出提案。
     *
     * <p>⚠️ 这会真的调用大模型（消耗额度），所以做成**手动触发**，
     * 不做成"到阈值就自动跑" —— 花钱的事不该在管理员不知情时发生。
     */
    @PostMapping("/analyze")
    public ResponseEntity<?> analyze(@RequestBody(required = false) Map<String, Object> body) {
        int max = 20;
        if (body != null && body.get("max") instanceof Number n) {
            max = n.intValue();
        }
        try {
            KbProposalService.AnalyzeResult r = service.analyze(max);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ok", true);
            out.put("message", r.message());
            out.put("goodTotal", r.goodTotal());
            out.put("analyzed", r.analyzed());
            out.put("proposals", r.proposals());
            out.put("stats", service.stats());
            return ResponseEntity.ok(out);
        } catch (Exception e) {
            log.warn("[PROPOSAL] 分析失败：{}", e.getMessage());
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    /** 人工确认或拒绝。approve=false 时只标记，不动知识库 */
    @PostMapping("/review")
    public ResponseEntity<?> review(@RequestBody Map<String, Object> body) {
        Object idRaw = body.get("id");
        if (!(idRaw instanceof Number num)) {
            return ResponseEntity.badRequest().body(Map.of("error", "需要 id"));
        }
        boolean approve = Boolean.TRUE.equals(body.get("approve"));
        String msg = service.review(num.longValue(), approve, "human");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("message", msg);
        out.put("stats", service.stats());
        return ResponseEntity.ok(out);
    }
}
