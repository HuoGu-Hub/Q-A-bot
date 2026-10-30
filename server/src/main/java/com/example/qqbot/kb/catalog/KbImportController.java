package com.example.qqbot.kb.catalog;

import io.swagger.v3.oas.annotations.security.SecurityRequirement;

import io.swagger.v3.oas.annotations.tags.Tag;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import com.example.qqbot.kb.block.KbDocImporter;
import com.example.qqbot.web.ApiException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 文档导入接口 —— 「文档」面板真正导入的入口。
 *
 * <p>导入一份文档 = **自动切割成一个个单独的词条**：文件里每个块落成一个词条，
 * 块 id 就是词条身份，块标题就是词条标题。文件里出现的 id 存在就覆盖、不存在就新增；
 * 没出现的块一个都不动。别名不会被覆盖。
 */
@Tag(name = "Admin")
@SecurityRequirement(name = "adminSession")
@RestController
@RequestMapping("/admin/api/kb/blocks")
public class KbImportController {

    private final KbCatalog catalog;

    public KbImportController(KbCatalog catalog) {
        this.catalog = catalog;
    }

    /** 真正导入：解析、切割、落块（= 词条）、重算向量 */
    @PostMapping("/imports")
    public ResponseEntity<Map<String, Object>> importDoc(@RequestBody ImportRequest body) {
        try {
            KbDocImporter.Result r = catalog.importDocument(body.text());
            return ResponseEntity.ok(Map.of("ok", true,
                    "added", r.added(), "updated", r.updated(),
                    "unchanged", r.unchanged(), "untouched", r.untouched(),
                    "warnings", r.warnings()));
        } catch (IllegalArgumentException | IllegalStateException e) {
            throw ApiException.badRequest(e.getMessage() == null ? "请求有误" : e.getMessage());
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = false)

    public record ImportRequest(String text) {
    }
}
