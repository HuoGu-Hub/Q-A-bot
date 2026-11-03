package com.example.qqbot.eval;

import com.example.qqbot.admin.AdminAuthFilter;
import com.example.qqbot.admin.AdminClientIp;
import com.example.qqbot.admin.AdminPrincipal;
import com.example.qqbot.persistence.AdminAuditRepository;
import com.example.qqbot.persistence.EvalRepository;
import com.example.qqbot.web.ApiException;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.servlet.http.HttpServletRequest;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 评测中台的只读与动作接口。
 *
 * <p>全部在 {@code /admin/api/eval} 之下，认证与角色范围由 {@code AdminAuthFilter} 统一处理，
 * 本类只关心数据。
 *
 * <h2>P0 的边界</h2>
 * 建批、执行、查询、单条用例维护已经可用；重跑失败项、抽检、人工复核、基线门禁还没有入口。
 * 前端对这些动作只做展示，不提供假按钮 —— 点下去报错的按钮比没有按钮更糟。
 */
@Tag(name = "Eval")
@SecurityRequirement(name = "adminSession")
@RestController
@RequestMapping("/admin/api/eval")
public class EvalController {

    /** 建批人：P0 用固定标识，接入账号体系后换成登录名 */
    private static final String ACTOR = "admin";

    private final EvalService service;
    private final EvalDatasetService datasets;
    private final EvalRepository repo;
    private final AdminAuditRepository audit;
    private final EvalReport report;

    public EvalController(EvalService service, EvalDatasetService datasets, EvalRepository repo,
                          AdminAuditRepository audit, EvalReport report) {
        this.service = service;
        this.datasets = datasets;
        this.repo = repo;
        this.audit = audit;
        this.report = report;
    }

    /** 评测的写操作进 admin_audit：出了事要能查到是谁建的批、改的判定 */
    private void audit(HttpServletRequest request, String action, String detail) {
        try {
            AdminPrincipal p = AdminAuthFilter.current(request);
            String ip = AdminAuthFilter.hashIp(AdminClientIp.of(request));
            audit.record(p == null ? null : p.id(), p == null ? ACTOR : p.username(),
                    "eval." + action, "POST",
                    request == null ? "" : request.getRequestURI(), detail, ip);
        } catch (RuntimeException ignored) {
            // 审计失败不该让业务动作回滚
        }
    }

    /* ==================== 用例集 ==================== */

    @GetMapping("/datasets")
    public List<EvalViews.Dataset> datasets() {
        return datasets.list();
    }

    @PostMapping("/datasets")
    public ResponseEntity<EvalViews.IdResult> createDataset(@RequestBody CreateDataset body) {
        if (body.name() == null || body.name().isBlank()) {
            throw ApiException.badRequest("需要用例集名称");
        }
        EvalViews.Dataset created = service.createDataset(body.slug(), body.name(),
                body.description(), ACTOR);
        return ResponseEntity.status(HttpStatus.CREATED).body(new EvalViews.IdResult(created.id()));
    }

    @GetMapping("/datasets/{id}")
    public EvalViews.DatasetDetail dataset(@PathVariable long id,
                                           @RequestParam(defaultValue = "200") int caseLimit) {
        EvalViews.DatasetDetail detail = datasets.detail(id, Math.min(Math.max(caseLimit, 1), 1000));
        if (detail == null) {
            throw ApiException.notFound("用例集不存在");
        }
        return detail;
    }

    @PostMapping("/datasets/{id}/cases")
    public ResponseEntity<EvalViews.CaseView> addCase(@PathVariable long id,
                                                      @RequestBody AddCase body) {
        EvalViews.CaseView saved = datasets.addCase(id, body.caseKey(), body.category(),
                body.question(), body.expected(), body.note());
        if (saved == null) {
            throw ApiException.badRequest("问题不能为空");
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(saved);
    }

    @PostMapping("/datasets/{id}/actions/import")
    public EvalViews.ImportResult importTsv(@PathVariable long id, @RequestBody ImportRequest body,
                                            HttpServletRequest request) {
        EvalViews.ImportResult result = datasets.importTsv(id, body.content(),
                Boolean.TRUE.equals(body.dryRun()));
        if (!result.dryRun() && result.accepted() > 0) {
            audit(request, "import", "dataset=" + id + " accepted=" + result.accepted()
                    + " rejected=" + result.rejected());
        }
        return result;
    }

    @PostMapping("/datasets/{id}/actions/freeze")
    public ResponseEntity<EvalViews.Version> freeze(@PathVariable long id,
                                                    HttpServletRequest request) {
        EvalViews.Version version = datasets.freeze(id, ACTOR);
        if (version == null) {
            throw ApiException.badRequest("用例集为空，不能冻结");
        }
        audit(request, "dataset.freeze", "dataset=" + id + " version=" + version.version());
        return ResponseEntity.status(HttpStatus.CREATED).body(version);
    }

    @PatchMapping("/datasets/{id}/cases/{caseId}")
    public EvalViews.CaseView updateCase(@PathVariable long id, @PathVariable long caseId,
                                         @RequestBody UpdateCase body, HttpServletRequest request) {
        EvalViews.CaseView view = service.updateCase(id, caseId, body.question(), body.expected(),
                body.note(), body.enabled());
        audit(request, "dataset.case.update", "dataset=" + id + " case=" + caseId);
        return view;
    }

    @DeleteMapping("/datasets/{id}/cases/{caseId}")
    public ResponseEntity<Void> deleteCase(@PathVariable long id, @PathVariable long caseId,
                                           HttpServletRequest request) {
        service.deleteCase(id, caseId);
        audit(request, "dataset.case.delete", "dataset=" + id + " case=" + caseId);
        return ResponseEntity.noContent().build();
    }

    /* ==================== 批次 ==================== */

    @GetMapping("/batches")
    public List<EvalViews.Batch> batches(@RequestParam(defaultValue = "50") int limit,
                                         @RequestParam(defaultValue = "0") int offset) {
        return service.listBatches(Math.min(Math.max(limit, 1), 200), Math.max(offset, 0));
    }

    @PostMapping("/batches")
    public ResponseEntity<EvalViews.IdResult> createBatch(@RequestBody CreateBatch body,
                                                          HttpServletRequest request) {
        long id = service.createBatch(body.name(), body.datasetId(), body.versionId(),
                body.repeatTimes() == null ? 1 : body.repeatTimes(),
                body.concurrency() == null ? 2 : body.concurrency(),
                body.mode(), body.sampleRate() == null ? 1.0 : body.sampleRate(), ACTOR);
        audit(request, "batch.create", "batch=" + id + " dataset=" + body.datasetId());
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(new EvalViews.IdResult(id));
    }

    @GetMapping("/batches/{id}")
    public EvalViews.BatchDetail batch(@PathVariable long id,
                                       @RequestParam(defaultValue = "200") int itemLimit) {
        EvalViews.BatchDetail detail = service.batchDetail(id, Math.min(Math.max(itemLimit, 1), 1000));
        if (detail == null) {
            throw ApiException.notFound("批次不存在");
        }
        return detail;
    }

    /**
     * 批次动作。P0 只支持克隆：用同一个用例集再跑一批。
     *
     * <p>暂停、取消、重跑失败项都会改动正在执行的状态，需要执行器配合，
     * 留到执行器支持中断之后再做 —— 先给一个能用的，而不是四个点了会坏的。
     */
    @PostMapping("/batches/{id}/actions/{verb}")
    public ResponseEntity<EvalViews.IdResult> batchAction(@PathVariable long id,
                                                          @PathVariable String verb,
                                                          HttpServletRequest request) {
        EvalRepository.BatchRow batch = repo.findBatch(id);
        if (batch == null) {
            throw ApiException.notFound("批次不存在");
        }
        if ("cancel".equals(verb)) {
            if (!service.cancelBatch(id)) {
                throw ApiException.badRequest("这个批次已经结束，不能取消");
            }
            audit(request, "batch.cancel", "batch=" + id);
            return ResponseEntity.noContent().build();
        }
        if ("retry-failed".equals(verb)) {
            long newId = service.retryFailed(id);
            audit(request, "batch.retry_failed", "from=" + id + " to=" + newId);
            return ResponseEntity.status(HttpStatus.ACCEPTED)
                    .body(new EvalViews.IdResult(newId));
        }
        if (!"clone".equals(verb)) {
            throw ApiException.badRequest("暂不支持该动作：" + verb);
        }
        long newId = service.createBatch(batch.name() + " 副本", batch.datasetId(),
                batch.datasetVersionId(), batch.repeatTimes(), batch.concurrency(), batch.mode(),
                batch.sampleRate(), ACTOR);
        audit(request, "batch.clone", "from=" + id + " to=" + newId);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(new EvalViews.IdResult(newId));
    }

    /** 单文件 HTML 报告，可直接归档或转发；P1 为即时生成，不是不可变快照 */
    @GetMapping(value = "/batches/{id}/report", produces = "text/html;charset=UTF-8")
    public ResponseEntity<String> report(@PathVariable long id) {
        String html = report.html(id);
        if (html == null) {
            throw ApiException.notFound("批次不存在");
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"eval-batch-" + id + ".html\"")
                .contentType(MediaType.TEXT_HTML)
                .body(html);
    }

    /** 失败归因下钻：tag 为空取全部 */
    @GetMapping("/batches/{id}/failures")
    public List<EvalViews.FailureCase> failures(@PathVariable long id,
                                                @RequestParam(required = false) String tag,
                                                @RequestParam(defaultValue = "200") int limit) {
        return service.failures(id, tag, limit);
    }

    /* ==================== 单条执行 ==================== */

    @GetMapping("/runs/{id}")
    public EvalViews.RunDetail run(@PathVariable long id) {
        EvalViews.RunDetail detail = service.runDetail(id);
        if (detail == null) {
            throw ApiException.notFound("执行记录不存在");
        }
        return detail;
    }

    /* ==================== 趋势与对比 ==================== */

    @GetMapping("/trends")
    public List<EvalViews.TrendPoint> trends(@RequestParam long datasetId,
                                             @RequestParam(defaultValue = "20") int limit) {
        return service.trends(datasetId, limit);
    }

    @GetMapping("/compare")
    public EvalViews.CompareResult compare(@RequestParam long left, @RequestParam long right) {
        return service.compare(left, right);
    }

    /* ==================== 告警 ==================== */

    @GetMapping("/alerts/rules")
    public List<EvalViews.AlertRule> alertRules() {
        return service.alertRules();
    }

    @PostMapping("/alerts/rules")
    public ResponseEntity<EvalViews.AlertRule> createAlertRule(@RequestBody CreateAlertRule body,
                                                               HttpServletRequest request) {
        EvalViews.AlertRule rule = service.createAlertRule(body.name(), body.metric(),
                body.comparator(), body.threshold() == null ? 0 : body.threshold(), body.severity());
        audit(request, "alert.rule.create", "rule=" + rule.id() + " metric=" + rule.metric());
        return ResponseEntity.status(HttpStatus.CREATED).body(rule);
    }

    @PostMapping("/alerts/rules/{id}/actions/{verb}")
    public ResponseEntity<Void> alertRuleAction(@PathVariable long id, @PathVariable String verb,
                                                HttpServletRequest request) {
        boolean enable = "enable".equals(verb);
        if (!enable && !"disable".equals(verb)) {
            throw ApiException.badRequest("暂不支持该动作：" + verb);
        }
        if (!service.toggleAlertRule(id, enable)) {
            throw ApiException.notFound("规则不存在");
        }
        audit(request, "alert.rule." + verb, "rule=" + id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/alerts/events")
    public List<EvalViews.AlertEvent> alertEvents(@RequestParam(required = false) String status,
                                                  @RequestParam(defaultValue = "50") int limit) {
        return service.alertEvents(status, limit);
    }

    @PostMapping("/alerts/events/{id}/actions/ack")
    public ResponseEntity<Void> ackAlert(@PathVariable long id,
                                         @RequestBody(required = false) AckAlert body,
                                         HttpServletRequest request) {
        if (!service.ackAlert(id, body == null ? null : body.note())) {
            throw ApiException.notFound("事件不存在或已处置");
        }
        audit(request, "alert.ack", "event=" + id);
        return ResponseEntity.noContent().build();
    }

    /* ==================== 价目 ==================== */

    @GetMapping("/prices")
    public List<EvalViews.Price> prices() {
        return service.prices();
    }

    /** 价目按"每百万 token 多少元"填，成本在服务端换算成微元 */
    @PutMapping("/prices")
    public EvalViews.Price upsertPrice(@RequestBody UpsertPrice body, HttpServletRequest request) {
        if (body.inputPerMillion() == null || body.outputPerMillion() == null) {
            throw ApiException.badRequest("需要输入与输出单价");
        }
        return service.upsertPrice(body.provider(), body.model(),
                body.inputPerMillion(), body.outputPerMillion(), body.currency());
    }

    /* ==================== 基线 ==================== */

    @GetMapping("/baselines")
    public List<EvalViews.Baseline> baselines(@RequestParam(required = false) Long datasetVersionId,
                                              @RequestParam(required = false) String status,
                                              @RequestParam(defaultValue = "20") int limit) {
        return service.baselines(datasetVersionId, status, limit);
    }

    @PostMapping("/baselines")
    public ResponseEntity<EvalViews.Baseline> createBaseline(@RequestBody CreateBaseline body,
                                                             HttpServletRequest request) {
        if (body.batchId() == null) {
            throw ApiException.badRequest("需要 batchId");
        }
        EvalViews.Baseline baseline = service.createBaseline(body.batchId(), body.name());
        audit(request, "baseline.create", "baseline=" + baseline.id() + " batch=" + body.batchId());
        return ResponseEntity.status(HttpStatus.CREATED).body(baseline);
    }

    @PostMapping("/baselines/{id}/actions/promote")
    public ResponseEntity<Void> promoteBaseline(@PathVariable long id, HttpServletRequest request) {
        if (!service.promoteBaseline(id, ACTOR)) {
            throw ApiException.notFound("基线不存在");
        }
        audit(request, "baseline.promote", "baseline=" + id);
        return ResponseEntity.noContent().build();
    }

    /* ==================== 复核 ==================== */

    @GetMapping("/reviews")
    public List<EvalViews.Review> reviews(@RequestParam(required = false) String status,
                                          @RequestParam(defaultValue = "50") int limit) {
        return service.reviews(status, limit);
    }

    @PostMapping("/runs/{id}/actions/review")
    public ResponseEntity<EvalViews.IdResult> requestReview(@PathVariable long id,
                                                            @RequestBody(required = false) ReviewRequest body) {
        long reviewId = service.requestReview(id, body == null ? null : body.reason());
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(new EvalViews.IdResult(reviewId));
    }

    @PostMapping("/reviews/{id}/actions/claim")
    public ResponseEntity<Void> claimReview(@PathVariable long id) {
        if (!service.claimReview(id, ACTOR)) {
            throw ApiException.badRequest("任务已被认领或已提交");
        }
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/reviews/{id}/actions/submit")
    public ResponseEntity<Void> submitReview(@PathVariable long id, @RequestBody SubmitReview body,
                                             HttpServletRequest request) {
        if (body.verdict() == null) {
            throw ApiException.badRequest("需要 verdict");
        }
        if (!service.submitReview(id, body.verdict(), body.note(), ACTOR)) {
            throw ApiException.notFound("复核任务不存在或已提交");
        }
        audit(request, "review.submit", "review=" + id + " verdict=" + body.verdict());
        return ResponseEntity.noContent().build();
    }

    /* ==================== 请求体 ==================== */

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record CreateDataset(String slug, String name, String description) {
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record AddCase(String caseKey, String category, String question, String expected,
                          String note) {
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record ImportRequest(String content, Boolean dryRun) {
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record UpdateCase(String question, String expected, String note, Boolean enabled) {
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record CreateAlertRule(String name, String metric, String comparator, Double threshold,
                                  String severity) {
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record AckAlert(String note) {
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record UpsertPrice(String provider, String model, Double inputPerMillion,
                              Double outputPerMillion, String currency) {
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record CreateBaseline(Long batchId, String name) {
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record ReviewRequest(String reason) {
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record SubmitReview(String verdict, String note) {
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record CreateBatch(String name, Long datasetId, Long versionId, Integer repeatTimes,
                              Integer concurrency, String mode, Double sampleRate) {
    }
}
