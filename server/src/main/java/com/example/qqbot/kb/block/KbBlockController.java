package com.example.qqbot.kb.block;

import com.example.qqbot.kb.doc.ChunkImportPlan;
import com.example.qqbot.kb.doc.ChunkMarkup;
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
 */
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

    /** 改一块的正文（会重算向量） */
    @PostMapping("/update")
    public ResponseEntity<Map<String, Object>> update(@RequestBody Map<String, Object> body) {
        String id = str(body.get("id"));
        if (id == null) {
            return bad("需要 id");
        }
        try {
            KbBlockAdminService.BlockView view = service.updateText(id, str(body.get("text")));
            if (view == null) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "没有这个块：" + id));
            }
            return ResponseEntity.ok(Map.of("ok", true, "block", view));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return bad(e.getMessage());
        }
    }

    /** 新增一块（id 建议显式给，见服务层说明） */
    @PostMapping("/add")
    public ResponseEntity<Map<String, Object>> add(@RequestBody Map<String, Object> body) {
        try {
            KbBlockAdminService.BlockView view = service.add(
                    str(body.get("docId")), str(body.get("id")), str(body.get("title")),
                    str(body.get("text")), str(body.get("url")), tags(body.get("tags")));
            return ResponseEntity.ok(Map.of("ok", true, "block", view));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return bad(e.getMessage());
        }
    }

    /** 下架 / 恢复一块（保留数据） */
    @PostMapping("/retire")
    public ResponseEntity<Map<String, Object>> retire(@RequestBody Map<String, Object> body) {
        String id = str(body.get("id"));
        boolean retired = !Boolean.FALSE.equals(body.get("retired"));
        if (id == null) {
            return bad("需要 id");
        }
        return service.setRetired(id, retired)
                ? ResponseEntity.ok(Map.of("ok", true, "retired", retired))
                : ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "没有这个块：" + id));
    }

    /** **真删除**一块（不可恢复；旧设计只能打墓碑） */
    @PostMapping("/delete")
    public ResponseEntity<Map<String, Object>> delete(@RequestBody Map<String, Object> body) {
        String id = str(body.get("id"));
        if (id == null) {
            return bad("需要 id");
        }
        return service.delete(id)
                ? ResponseEntity.ok(Map.of("ok", true))
                : ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "没有这个块：" + id));
    }

    /** 整份文档下架 / 恢复 */
    @PostMapping("/retire-doc")
    public ResponseEntity<Map<String, Object>> retireDoc(@RequestBody Map<String, Object> body) {
        String doc = str(body.get("doc"));
        boolean retired = !Boolean.FALSE.equals(body.get("retired"));
        if (doc == null) {
            return bad("需要 doc");
        }
        int n = service.setDocRetired(doc, retired);
        return ResponseEntity.ok(Map.of("ok", true, "affected", n,
                "message", (retired ? "已下架 " : "已恢复 ") + n + " 个文本块"));
    }

    /**
     * **导入前预览** —— 只算不改，回答"这次会改动多少条"。
     *
     * <p>这是用户明确要的能力：文件里出现的 id 存在就覆盖、不存在就新增；
     * 没出现的块一个都不动。
     */
    @PostMapping("/preview")
    public ResponseEntity<Map<String, Object>> preview(@RequestBody Map<String, Object> body) {
        try {
            ChunkImportPlan<ChunkMarkup.Block> plan = service.preview(str(body.get("text")));
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
            return bad(e.getMessage());
        }
    }

    /** 真正导入 */
    @PostMapping("/import")
    public ResponseEntity<Map<String, Object>> importDoc(@RequestBody Map<String, Object> body) {
        try {
            KbDocImporter.Result r = service.importDoc(str(body.get("text")));
            return ResponseEntity.ok(Map.of("ok", true,
                    "added", r.added(), "updated", r.updated(),
                    "unchanged", r.unchanged(), "untouched", r.untouched(),
                    // 导入一份文档 = 这个页面在词条表里占一行（词条是"按页定位分块"的入口）
                    "terms", r.terms(),
                    "warnings", r.warnings()));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return bad(e.getMessage());
        }
    }

    /**
     * 补建 / 刷新**标题向量**（C 路）—— 幂等，只补"缺失"和"过期"的块。
     *
     * <p>传 {@code {"dryRun": true}} 先只看要补多少条（不调模型、不花钱）；
     * 直接 POST 空 body 就是真补。补完索引自动重载，不用重启。
     */
    @PostMapping("/title-vectors/backfill")
    public ResponseEntity<Map<String, Object>> backfillTitleVectors(
            @RequestBody(required = false) Map<String, Object> body) {
        boolean dryRun = body != null && Boolean.TRUE.equals(body.get("dryRun"));
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
            return bad(e.getMessage());
        }
    }

    /** 清空全部块 —— 首次导入中文语料前用。**不可逆** */
    @PostMapping("/clear")
    public ResponseEntity<Map<String, Object>> clear() {
        try {
            service.clearAll();
            return ResponseEntity.ok(Map.of("ok", true, "message", "已清空全部块与向量"));
        } catch (Exception e) {
            log.warn("[KB-BLOCK] 清空失败：{}", e.getMessage());
            return bad(e.getMessage());
        }
    }

    // ==================== 内部 ====================

    private static ResponseEntity<Map<String, Object>> bad(String msg) {
        return ResponseEntity.badRequest().body(Map.of("error", msg == null ? "请求有误" : msg));
    }

    private static String str(Object o) {
        if (o == null) {
            return null;
        }
        String s = String.valueOf(o).trim();
        return s.isEmpty() ? null : s;
    }

    @SuppressWarnings("unchecked")
    private static List<String> tags(Object o) {
        if (o instanceof List<?> list) {
            return list.stream().filter(x -> x != null && !String.valueOf(x).isBlank())
                    .map(x -> String.valueOf(x).trim()).toList();
        }
        String s = str(o);
        return s == null ? List.of() : ChunkMarkup.splitTags(s);
    }
}
