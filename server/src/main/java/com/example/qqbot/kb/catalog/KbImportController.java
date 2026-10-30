package com.example.qqbot.kb.catalog;

import com.example.qqbot.kb.block.KbDocImporter;
import com.example.qqbot.web.ApiException;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 文档导入接口 —— 「文档」面板真正导入的入口。
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
    public ImportResult importDoc(@RequestBody ImportRequest body) {
        try {
            KbDocImporter.Result r = catalog.importDocument(body.text());
            return new ImportResult(r.added(), r.updated(), r.unchanged(), r.untouched(),
                    r.warnings());
        } catch (IllegalArgumentException | IllegalStateException e) {
            throw ApiException.badRequest(e.getMessage() == null ? "请求有误" : e.getMessage());
        }
    }

    public record ImportResult(int added, int updated, int unchanged, int untouched,
                               List<String> warnings) {
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record ImportRequest(String text) {
    }
}
