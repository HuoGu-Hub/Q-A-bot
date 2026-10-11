package com.example.qqbot.kb.catalog;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 文档导入接口 —— <b>跨两个真源的用例，所以住在组合层</b>。
 *
 * <p>"导入一份文档"= 落块 + 登记名字，两步缺一不可。块层只做前半段，
 * 而它不认识名字层；名字层不认识块层。能同时看到两边的只有这里。
 *
 * <p>⚠️ 块层<strong>不再</strong>暴露"只落块"的 HTTP 导入接口：
 * 绕过名字登记不会报错，只会让新页面的中文提问静默够不到 ——
 * 这类错误在日志里看不出来，只有"词条表少了几条"。
 *
 * <p>路径与块管理接口同前缀，但归属不同：块管理的其余端点是块自己的事，
 * 这一条是组合层的用例。
 */
@RestController
@RequestMapping("/admin/api/kb/blocks")
public class KbImportController {

    private final KbCatalog catalog;

    public KbImportController(KbCatalog catalog) {
        this.catalog = catalog;
    }

    /** 真正导入：落块 + 登记名字，一次调用把两半都做完 */
    @PostMapping("/import")
    public ResponseEntity<Map<String, Object>> importDoc(@RequestBody Map<String, Object> body) {
        try {
            KbCatalog.ImportOutcome out = catalog.importDocument(str(body.get("text")));
            var r = out.blocks();
            return ResponseEntity.ok(Map.of("ok", true,
                    "added", r.added(), "updated", r.updated(),
                    "unchanged", r.unchanged(), "untouched", r.untouched(),
                    // 导入一份文档 = 这些块在词条表里各占一行（词条是"按页定位分块"的入口）
                    "terms", out.terms(),
                    "warnings", r.warnings()));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return bad(e.getMessage());
        }
    }

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
}
