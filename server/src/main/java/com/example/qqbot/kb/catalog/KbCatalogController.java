package com.example.qqbot.kb.catalog;

import io.swagger.v3.oas.annotations.security.SecurityRequirement;

import io.swagger.v3.oas.annotations.tags.Tag;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import com.example.qqbot.web.ApiException;

import com.example.qqbot.kb.block.KbBlockAdminService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
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
import java.util.Locale;
import java.util.Map;

/**
 * 知识库词条接口 —— 「知识库」页的「词条」二级目录用。
 *
 * <p><b>一个词条 = 一个分块</b>：列表直接列出块，字段是 {@code id / 标题 / 别名 / 分类}。
 * 核对状态与核对模式已经全部删掉，所以这里没有状态相关的任何端点。
 *
 * <p>别名是唯一可写的字段（零成本、不重算向量）；正文改在「文档」面板。
 */
@Tag(name = "Admin")
@SecurityRequirement(name = "adminSession")
@RestController
@RequestMapping("/admin/api/kb/terms")
public class KbCatalogController {

    private static final Logger log = LoggerFactory.getLogger(KbCatalogController.class);

    private final KbCatalog catalog;
    private final KbBlockAdminService blockAdmin;

    public KbCatalogController(KbCatalog catalog, KbBlockAdminService blockAdmin) {
        this.catalog = catalog;
        this.blockAdmin = blockAdmin;
    }

    /**
     * 词条列表 + 全表读数 + 分类规模。
     *
     * @param q        按 id / 标题 / 别名 / 文档模糊搜
     * @param view     视图预设，见 {@link KbCatalog#VIEWS}
     * @param category 只看某个分类（或「未分类」）
     */
    @GetMapping("")
    public ResponseEntity<KbCatalog.Page> list(@RequestParam(defaultValue = "") String q,
                                  @RequestParam(defaultValue = "") String view,
                                  @RequestParam(defaultValue = "") String category,
                                  @RequestParam(defaultValue = "100") int limit,
                                  @RequestParam(defaultValue = "0") int offset) {
        String v = view == null ? "" : view.trim().toLowerCase(Locale.ROOT);
        if (!v.isEmpty() && !KbCatalog.VIEWS.contains(v)) {
            throw ApiException.badRequest("view 只能是 " + String.join(" / ", KbCatalog.VIEWS));
        }
        int lim = Math.min(Math.max(1, limit), 500);
        return ResponseEntity.ok(catalog.page(q, v, category, lim, Math.max(0, offset)));
    }

    /**
     * 写一条词条的别名。
     *
     * <p>body：{@code {id, alias}}。空别名 = 回到标题派生的别名。
     * 写完**立即生效**，不需要重启机器人。
     */
    @PatchMapping("/aliases")
    public ResponseEntity<AliasResult> saveAlias(@RequestBody AliasRequest body) {
        String id = body.id() == null ? null : body.id().trim();
        if (id == null || id.isBlank()) {
            throw ApiException.badRequest("id 不能为空");
        }
        String alias = body.alias() == null ? "" : body.alias().trim();
        KbBlockAdminService.BlockView view = blockAdmin.updateAlias(id, alias);
        if (view == null) {
            throw ApiException.notFound("没有这个块：" + id);
        }
        return ResponseEntity.ok(new AliasResult(view));
    }

    /** 清掉一条的别名（回到标题派生） */
    @DeleteMapping("/aliases")
    public ResponseEntity<Void> clear(@RequestParam("id") String id) {
        if (id == null || id.isBlank()) {
            throw ApiException.badRequest("需要 id");
        }
        if (blockAdmin.updateAlias(id, "") == null) {
            throw ApiException.notFound("没有这个块：" + id);
        }
        return ResponseEntity.noContent().build();
    }

    /** 清空**全部**别名 —— 不可逆，删的是纯人工成果 */
    @DeleteMapping("/aliases/all")
    public ResponseEntity<Cleared> clearAll(@RequestParam(name = "confirm", defaultValue = "false") boolean confirm) {
        if (!confirm) {
            throw ApiException.badRequest("危险操作：请带 confirm=true");
        }
        int n = 0;
        for (KbCatalog.Entry e : catalog.page("", "aliased", "", Integer.MAX_VALUE, 0).items()) {
            if (blockAdmin.updateAlias(e.id(), "") != null) {
                n++;
            }
        }
        log.warn("[KB-TERM] 已清空全部别名：{} 条（不可逆）", n);
        return ResponseEntity.ok(new Cleared(n));
    }

    // ==================== 导出 / 导入（Excel 旁路）====================

    /**
     * 导出当前视图。
     *
     * @param format {@code xlsx}（默认）或 {@code tsv}
     *               —— CSV 已弃用：中文 Windows 上 GBK 会静默乱码
     */
    @GetMapping("/export")
    public ExportResult export(@RequestParam(defaultValue = "") String q,
                                      @RequestParam(defaultValue = "") String view,
                                      @RequestParam(defaultValue = "") String category,
                                      @RequestParam(defaultValue = "xlsx") String format) {
        String fmt = format.trim().toLowerCase(Locale.ROOT);
        if ("csv".equals(fmt)) {
            throw ApiException.badRequest("CSV 导出已弃用（中文 Windows 上 GBK 会静默乱码）："
                    + "请用 format=xlsx（人 / Excel）或 format=tsv（机机）");
        }
        String tsv = stripBom(catalog.exportTsv(q, view, category));
        long count = tsv.lines().filter(l -> !l.isBlank() && l.charAt(0) != '#').count();

        if ("tsv".equals(fmt)) {
            return new ExportResult(count, tsv, tsv, "tsv", null);
        }
        return new ExportResult(count, null, null, "xlsx",
                java.util.Base64.getEncoder().encodeToString(catalog.exportXlsx(q, view, category)));
    }

    /** 从 TSV / CSV / xlsx 导入别名表（只按 id 覆盖别名） */
    @PostMapping("/imports")
    public ResponseEntity<ImportTableResult> importTable(@RequestBody ImportTableRequest body) {
        String tsv = body.tsv();
        if (tsv == null || tsv.isBlank()) {
            tsv = body.csv();
        }
        String b64 = body.xlsxBase64();
        if ((tsv == null || tsv.isBlank()) && b64 != null && !b64.isBlank()) {
            try {
                tsv = EntryXlsx.read(java.util.Base64.getDecoder().decode(b64.trim()));
            } catch (IllegalArgumentException | java.io.UncheckedIOException e) {
                throw ApiException.badRequest("xlsx 读不出来：" + e.getMessage());
            }
        }
        if (tsv == null || tsv.isBlank()) {
            throw ApiException.badRequest("表内容不能为空（tsv / csv / xlsxBase64）");
        }
        tsv = stripBom(tsv);
        if (tsv.indexOf('\uFFFD') >= 0) {
            throw ApiException.badRequest("文件编码不对：中文变成了乱码（U+FFFD）。请另存为「CSV UTF-8」，或直接用后台导出的 xlsx。");
        }
        KbCatalog.ImportResult r = catalog.importTable(tsv);
        return ResponseEntity.ok(new ImportTableResult(r.updated(), r.skipped()));
    }

    @JsonIgnoreProperties(ignoreUnknown = false)

    public record AliasRequest(String id, String alias) {
    }

    @JsonIgnoreProperties(ignoreUnknown = false)

    public record ImportTableRequest(String tsv, String csv, String xlsxBase64) {
    }

    private static String stripBom(String s) {
        return s != null && !s.isEmpty() && s.charAt(0) == '\uFEFF' ? s.substring(1) : s;
    }


    public record AliasResult(KbBlockAdminService.BlockView block) {
    }

    public record Cleared(int cleared) {
    }

    public record ExportResult(long count, String tsv, String text, String format,
                               String xlsxBase64) {
    }

    public record ImportTableResult(int updated, int skipped) {
    }
}
