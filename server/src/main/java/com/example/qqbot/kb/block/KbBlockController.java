package com.example.qqbot.kb.block;

import com.example.qqbot.kb.doc.ChunkImportPlan;
import com.example.qqbot.kb.doc.ChunkMarkup;
import com.example.qqbot.web.ApiException;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 知识库**块**的管理接口 —— 一切按 id（不再有行号）。
 *
 * <p>认证由 {@code AdminAuthFilter} 统一拦在前面，和别的管理接口一样。
 *
 * <p>块 id 可能含斜杠，不能安全地放进路径变量，所以单块操作用查询参数传 id，
 * 写操作用 HTTP 方法表达意图。
 */
@Tag(name = "Admin")
@SecurityRequirement(name = "adminSession")
@RestController
@RequestMapping("/admin/api/kb/blocks")
public class KbBlockController {

    private static final Logger log = LoggerFactory.getLogger(KbBlockController.class);

    private final KbBlockAdminService service;

    public KbBlockController(KbBlockAdminService service) {
        this.service = service;
    }

    /** 概览：多少块 / 多少文档 / 多少已下架 */
    @GetMapping("/stats")
    public BlockStats stats() {
        return new BlockStats(service.available(), service.stats());
    }

    /** 文档列表（管理端左侧目录） */
    @GetMapping("/docs")
    public DocList docs() {
        return new DocList(service.available(), service.docs());
    }

    /** 某份文档下的全部块 */
    @GetMapping("")
    public BlockList blocks(@RequestParam("doc") String doc) {
        return new BlockList(doc, service.blocksOfDoc(doc));
    }

    /**
     * 按 id 取**单块**（含正文）—— 「词条」面板编辑正文用。
     *
     * <p>词条列表刻意不带正文（三千多条全文太大），所以编辑前单独取一次。
     */
    @GetMapping("/item")
    public BlockItem item(@RequestParam("id") String id) {
        KbBlockAdminService.BlockView view = service.get(id);
        if (view == null) {
            throw ApiException.notFound("没有这个块：" + id);
        }
        return new BlockItem(view);
    }

    /** 新增一块（id 建议显式给，见服务层说明） */
    @PostMapping("")
    public BlockItem add(@Valid @RequestBody AddBlockRequest body) {
        try {
            return new BlockItem(service.add(
                    trim(body.docId()), trim(body.id()), trim(body.title()),
                    body.text(), trim(body.url()), body.tags()));
        } catch (IllegalArgumentException | IllegalStateException e) {
            throw ApiException.badRequest(e.getMessage());
        }
    }

    /**
     * 管理端「新增词条」—— 人只填标题 / 别名 / 正文 / 分类，id 与 docId 由服务层兜住。
     */
    @PostMapping("/manual")
    public BlockItem addManual(@Valid @RequestBody AddManualBlockRequest body) {
        try {
            return new BlockItem(service.addManual(
                    trim(body.title()), body.text(), trim(body.alias()), body.tags()));
        } catch (IllegalArgumentException | IllegalStateException e) {
            throw ApiException.badRequest(e.getMessage());
        }
    }

    /** 改一块的正文（会重算向量） */
    @PatchMapping("")
    public BlockItem update(@Valid @RequestBody UpdateBlockRequest body) {
        if (body.id() == null || body.id().isBlank()) {
            throw ApiException.badRequest("需要 id");
        }
        try {
            KbBlockAdminService.BlockView view = service.updateText(body.id(), body.text());
            if (view == null) {
                throw ApiException.notFound("没有这个块：" + body.id());
            }
            return new BlockItem(view);
        } catch (IllegalArgumentException | IllegalStateException e) {
            throw ApiException.badRequest(e.getMessage());
        }
    }

    /** 下架 / 恢复一块（保留数据） */
    @PatchMapping("/retire")
    public RetireResult retire(@Valid @RequestBody RetireBlockRequest body) {
        if (body.id() == null || body.id().isBlank()) {
            throw ApiException.badRequest("需要 id");
        }
        boolean retired = body.retired() == null || body.retired();
        if (!service.setRetired(body.id(), retired)) {
            throw ApiException.notFound("没有这个块：" + body.id());
        }
        return new RetireResult(retired);
    }

    /** **真删除**一块（不可恢复；旧设计只能打墓碑） */
    @DeleteMapping("")
    public ResponseEntity<Void> delete(@RequestParam("id") String id) {
        if (id == null || id.isBlank()) {
            throw ApiException.badRequest("需要 id");
        }
        if (!service.delete(id)) {
            throw ApiException.notFound("没有这个块：" + id);
        }
        return ResponseEntity.noContent().build();
    }

    /** 整份文档下架 / 恢复 */
    @PatchMapping("/doc")
    public RetireDocResult retireDoc(@Valid @RequestBody RetireDocRequest body) {
        if (body.doc() == null || body.doc().isBlank()) {
            throw ApiException.badRequest("需要 doc");
        }
        boolean retired = body.retired() == null || body.retired();
        int n = service.setDocRetired(body.doc(), retired);
        return new RetireDocResult(n, (retired ? "已下架 " : "已恢复 ") + n + " 个文本块");
    }

    /**
     * **导入前预览** —— 只算不改，回答"这次会改动多少条"。
     */
    @PostMapping("/imports/preview")
    public ImportPreview preview(@Valid @RequestBody PreviewImportRequest body) {
        try {
            ChunkImportPlan<ChunkMarkup.Block> plan = service.preview(body.text());
            return new ImportPreview(
                    plan.added().size(),
                    plan.updated().size(),
                    plan.unchanged().size(),
                    plan.untouchedExisting(),
                    plan.added().stream().map(ChunkMarkup.Block::key).limit(100).toList(),
                    plan.updated().stream().map(ChunkMarkup.Block::key).limit(100).toList(),
                    plan.warnings());
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest(e.getMessage());
        }
    }

    /**
     * 补建 / 刷新**标题向量**（C 路）—— 幂等，只补"缺失"和"过期"的块。
     */
    @PostMapping("/title-vectors/backfill")
    public BackfillResult backfillTitleVectors(
            @RequestBody(required = false) BackfillRequest body) {
        boolean dryRun = body != null && Boolean.TRUE.equals(body.dryRun());
        try {
            KbBlockAdminService.TitleBackfill r = service.backfillTitleVectors(dryRun);
            return new BackfillResult(dryRun, r.blocks(), r.missing(), r.stale(),
                    r.embedded(), r.pending(),
                    dryRun ? "有 " + r.pending() + " 个块需要补标题向量"
                            : "已补 " + r.embedded() + " 条标题向量，索引已重载");
        } catch (IllegalArgumentException | IllegalStateException e) {
            throw ApiException.badRequest(e.getMessage());
        }
    }

    /** 清空全部块 —— 首次导入中文语料前用。**不可逆** */
    @DeleteMapping("/all")
    public ResponseEntity<Void> clear() {
        try {
            service.clearAll();
            return ResponseEntity.noContent().build();
        } catch (Exception e) {
            log.warn("[KB-BLOCK] 清空失败：{}", e.getMessage());
            throw ApiException.badRequest(e.getMessage());
        }
    }

    // ==================== 出参 ====================

    public record BlockStats(boolean available, long blocks, long retired, long documents,
                             long vectors, long titleVectors) {
        BlockStats(boolean available, java.util.Map<String, Integer> stats) {
            this(available,
                    stats.getOrDefault("blocks", 0),
                    stats.getOrDefault("retired", 0),
                    stats.getOrDefault("documents", 0),
                    stats.getOrDefault("vectors", 0),
                    stats.getOrDefault("titleVectors", 0));
        }
    }

    public record DocList(boolean available, List<KbBlockAdminService.DocSummary> documents) {
    }

    public record BlockList(String doc, List<KbBlockAdminService.BlockView> blocks) {
    }

    public record BlockItem(KbBlockAdminService.BlockView block) {
    }

    public record RetireResult(boolean retired) {
    }

    public record RetireDocResult(int affected, String message) {
    }

    public record ImportPreview(int added, int updated, int unchanged, int untouchedExisting,
                                List<String> addedIds, List<String> updatedIds,
                                List<String> warnings) {
    }

    public record BackfillResult(boolean dryRun, int blocks, int missing, int stale,
                                 int embedded, int pending, String message) {
    }

    // ==================== 入参 ====================

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record AddBlockRequest(String docId, String id, String title, String text,
                                  String url, List<String> tags) {
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record AddManualBlockRequest(String title, String text, String alias, List<String> tags) {
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record UpdateBlockRequest(@NotBlank String id, String text) {
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record RetireBlockRequest(@NotBlank String id, Boolean retired) {
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record RetireDocRequest(@NotBlank String doc, Boolean retired) {
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record PreviewImportRequest(String text) {
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record BackfillRequest(Boolean dryRun) {
    }

    private static String trim(String value) {
        if (value == null) {
            return null;
        }
        String s = value.trim();
        return s.isEmpty() ? null : s;
    }
}
