package com.example.qqbot.kb.block;

import io.swagger.v3.oas.annotations.security.SecurityRequirement;

import io.swagger.v3.oas.annotations.tags.Tag;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import com.example.qqbot.kb.doc.ChunkImportPlan;
import com.example.qqbot.kb.doc.ChunkMarkup;
import com.example.qqbot.web.ApiException;
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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 知识库**块**的管理接口 —— 一切按 id（不再有行号）。
 *
 * <p>认证由 {@code AdminAuthFilter} 统一拦在前面，和别的管理接口一样。
 *
 * <p>路径前缀选 {@code /kb/blocks} 而不是并进旧的 {@code /kb/terms}：
 * 旧接口是按行号操作 {@code chunks.jsonl} 的，新接口按 id 操作数据库 ——
 * 语义完全不同，混在一个前缀下最容易让人点错。切过去之后旧的一整块会删掉。
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
    public Map<String, Object> stats() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("available", service.available());
        out.putAll(service.stats());
        return out;
    }

    /** 文档列表（管理端左侧目录） */
    @GetMapping("/docs")
    public Map<String, Object> docs() {
        return Map.of("available", service.available(), "documents", service.docs());
    }

    /** 某份文档下的全部块 */
    @GetMapping("")
    public Map<String, Object> blocks(@RequestParam("doc") String doc) {
        return Map.of("doc", doc, "blocks", service.blocksOfDoc(doc));
    }

    /**
     * 按 id 取**单块**（含正文）—— 「词条」面板编辑正文用。
     *
     * <p>词条列表刻意不带正文（三千多条全文太大），所以编辑前单独取一次。
     */
    @GetMapping("/item")
    public ResponseEntity<Map<String, Object>> item(@RequestParam("id") String id) {
        KbBlockAdminService.BlockView view = service.get(id);
        if (view == null) {
            throw ApiException.notFound("没有这个块：" + id);
        }
        return ResponseEntity.ok(Map.of("ok", true, "block", view));
    }

    /** 新增一块（id 建议显式给，见服务层说明） */
    @PostMapping("")
    public ResponseEntity<Map<String, Object>> add(@Valid @RequestBody AddBlockRequest body) {
        try {
            KbBlockAdminService.BlockView view = service.add(
                    trim(body.docId()), trim(body.id()), trim(body.title()),
                    body.text(), trim(body.url()), body.tags());
            return ResponseEntity.ok(Map.of("ok", true, "block", view));
        } catch (IllegalArgumentException | IllegalStateException e) {
            throw ApiException.badRequest(e.getMessage());
        }
    }

    /**
     * 管理端「新增词条」—— 人只填标题 / 别名 / 正文 / 分类，id 与 docId 由服务层兜住。
     *
     * <p>与 {@code POST /kb/blocks} 的区别：那条路要调用方自己给英文 id（脚本与导入用）；
     * 这一条是给人在后台手写一条用的。
     */
    @PostMapping("/manual")
    public ResponseEntity<Map<String, Object>> addManual(@Valid @RequestBody AddManualBlockRequest body) {
        try {
            KbBlockAdminService.BlockView view = service.addManual(
                    trim(body.title()), body.text(), trim(body.alias()), body.tags());
            return ResponseEntity.ok(Map.of("ok", true, "block", view));
        } catch (IllegalArgumentException | IllegalStateException e) {
            throw ApiException.badRequest(e.getMessage());
        }
    }

    /** 改一块的正文（会重算向量） */
    @PatchMapping("")
    public ResponseEntity<Map<String, Object>> update(@Valid @RequestBody UpdateBlockRequest body) {
        if (body.id() == null || body.id().isBlank()) {
            throw ApiException.badRequest("需要 id");
        }
        try {
            KbBlockAdminService.BlockView view = service.updateText(body.id(), body.text());
            if (view == null) {
                throw ApiException.notFound("没有这个块：" + body.id());
            }
            return ResponseEntity.ok(Map.of("ok", true, "block", view));
        } catch (IllegalArgumentException | IllegalStateException e) {
            throw ApiException.badRequest(e.getMessage());
        }
    }

    /** 下架 / 恢复一块（保留数据） */
    @PatchMapping("/retire")
    public ResponseEntity<Map<String, Object>> retire(@Valid @RequestBody RetireBlockRequest body) {
        if (body.id() == null || body.id().isBlank()) {
            throw ApiException.badRequest("需要 id");
        }
        boolean retired = body.retired() == null || body.retired();
        if (!service.setRetired(body.id(), retired)) {
            throw ApiException.notFound("没有这个块：" + body.id());
        }
        return ResponseEntity.ok(Map.of("ok", true, "retired", retired));
    }

    /** **真删除**一块（不可恢复；旧设计只能打墓碑） */
    @DeleteMapping("")
    public ResponseEntity<Map<String, Object>> delete(@RequestParam("id") String id) {
        if (id == null || id.isBlank()) {
            throw ApiException.badRequest("需要 id");
        }
        if (!service.delete(id)) {
            throw ApiException.notFound("没有这个块：" + id);
        }
        return ResponseEntity.ok(Map.of("ok", true));
    }

    /** 整份文档下架 / 恢复 */
    @PatchMapping("/doc")
    public ResponseEntity<Map<String, Object>> retireDoc(@Valid @RequestBody RetireDocRequest body) {
        if (body.doc() == null || body.doc().isBlank()) {
            throw ApiException.badRequest("需要 doc");
        }
        boolean retired = body.retired() == null || body.retired();
        int n = service.setDocRetired(body.doc(), retired);
        return ResponseEntity.ok(Map.of("ok", true, "affected", n,
                "message", (retired ? "已下架 " : "已恢复 ") + n + " 个文本块"));
    }

    /**
     * **导入前预览** —— 只算不改，回答"这次会改动多少条"。
     *
     * <p>这是用户明确要的能力：文件里出现的 id 存在就覆盖、不存在就新增；
     * 没出现的块一个都不动。
     */
    @PostMapping("/imports/preview")
    public ResponseEntity<Map<String, Object>> preview(@Valid @RequestBody PreviewImportRequest body) {
        try {
            ChunkImportPlan<ChunkMarkup.Block> plan = service.preview(body.text());
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ok", true);
            out.put("added", plan.added().size());
            out.put("updated", plan.updated().size());
            out.put("unchanged", plan.unchanged().size());
            out.put("untouchedExisting", plan.untouchedExisting());
            out.put("addedIds", plan.added().stream().map(ChunkMarkup.Block::key).limit(100).toList());
            out.put("updatedIds", plan.updated().stream().map(ChunkMarkup.Block::key).limit(100).toList());
            out.put("warnings", plan.warnings());
            return ResponseEntity.ok(out);
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest(e.getMessage());
        }
    }

    // ⚠️ 导入端点不在这里 —— 它住在组合层（{@code kb.catalog.KbImportController}）。
    //    "导入一份文档"是跨两个真源的用例：落块（本层）之后必须接着登记名字（名字层），
    //    而块层不认识名字层。只落块不登记名字不会报错，
    //    只会让新页面的中文提问静默够不到 —— 所以这里刻意不提供这条捷径。

    /**
     * 补建 / 刷新**标题向量**（C 路）—— 幂等，只补"缺失"和"过期"的块。
     *
     * <p>传 {@code {"dryRun": true}} 先只看要补多少条（不调模型、不花钱）；
     * 直接 POST 空 body 就是真补。补完索引自动重载，不用重启。
     */
    @PostMapping("/title-vectors/backfill")
    public ResponseEntity<Map<String, Object>> backfillTitleVectors(
            @RequestBody(required = false) BackfillRequest body) {
        boolean dryRun = body != null && Boolean.TRUE.equals(body.dryRun());
        try {
            KbBlockAdminService.TitleBackfill r = service.backfillTitleVectors(dryRun);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ok", true);
            out.put("dryRun", dryRun);
            out.put("blocks", r.blocks());
            out.put("missing", r.missing());
            out.put("stale", r.stale());
            out.put("embedded", r.embedded());
            out.put("pending", r.pending());
            out.put("message", dryRun
                    ? "有 " + r.pending() + " 个块需要补标题向量"
                    : "已补 " + r.embedded() + " 条标题向量，索引已重载");
            return ResponseEntity.ok(out);
        } catch (IllegalArgumentException | IllegalStateException e) {
            throw ApiException.badRequest(e.getMessage());
        }
    }

    /** 清空全部块 —— 首次导入中文语料前用。**不可逆** */
    @DeleteMapping("/all")
    public ResponseEntity<Map<String, Object>> clear() {
        try {
            service.clearAll();
            return ResponseEntity.ok(Map.of("ok", true, "message", "已清空全部块与向量"));
        } catch (Exception e) {
            log.warn("[KB-BLOCK] 清空失败：{}", e.getMessage());
            throw ApiException.badRequest(e.getMessage());
        }
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
