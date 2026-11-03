package com.example.qqbot.eval;

import com.example.qqbot.persistence.EvalRepository;
import com.example.qqbot.web.ApiException;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
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

    public EvalController(EvalService service, EvalDatasetService datasets, EvalRepository repo) {
        this.service = service;
        this.datasets = datasets;
        this.repo = repo;
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
    public EvalViews.ImportResult importTsv(@PathVariable long id, @RequestBody ImportRequest body) {
        return datasets.importTsv(id, body.content(), Boolean.TRUE.equals(body.dryRun()));
    }

    @PostMapping("/datasets/{id}/actions/freeze")
    public ResponseEntity<EvalViews.Version> freeze(@PathVariable long id) {
        EvalViews.Version version = datasets.freeze(id, ACTOR);
        if (version == null) {
            throw ApiException.badRequest("用例集为空，不能冻结");
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(version);
    }

    /* ==================== 批次 ==================== */

    @GetMapping("/batches")
    public List<EvalViews.Batch> batches(@RequestParam(defaultValue = "50") int limit,
                                         @RequestParam(defaultValue = "0") int offset) {
        return service.listBatches(Math.min(Math.max(limit, 1), 200), Math.max(offset, 0));
    }

    @PostMapping("/batches")
    public ResponseEntity<EvalViews.IdResult> createBatch(@RequestBody CreateBatch body) {
        long id = service.createBatch(body.name(), body.datasetId(), body.versionId(),
                body.repeatTimes() == null ? 1 : body.repeatTimes(),
                body.concurrency() == null ? 2 : body.concurrency(),
                body.mode(), body.sampleRate() == null ? 1.0 : body.sampleRate(), ACTOR);
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
                                                          @PathVariable String verb) {
        EvalRepository.BatchRow batch = repo.findBatch(id);
        if (batch == null) {
            throw ApiException.notFound("批次不存在");
        }
        if (!"clone".equals(verb)) {
            throw ApiException.badRequest("暂不支持该动作：" + verb);
        }
        long newId = service.createBatch(batch.name() + " 副本", batch.datasetId(),
                batch.datasetVersionId(), batch.repeatTimes(), batch.concurrency(), batch.mode(),
                batch.sampleRate(), ACTOR);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(new EvalViews.IdResult(newId));
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
    public record CreateBatch(String name, Long datasetId, Long versionId, Integer repeatTimes,
                              Integer concurrency, String mode, Double sampleRate) {
    }
}
