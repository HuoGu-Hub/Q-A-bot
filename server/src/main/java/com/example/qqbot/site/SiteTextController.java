package com.example.qqbot.site;

import io.swagger.v3.oas.annotations.security.SecurityRequirement;

import io.swagger.v3.oas.annotations.tags.Tag;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import com.example.qqbot.web.ApiException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 「页面信息」接口 —— 管理公开站的固定文案。
 *
 * <p>块的清单（有哪些页、每页有哪些块）由后端的注册表决定，前端只负责渲染。
 * 这样「哪些字可以改」只有一处真相，不会前后端各写一份清单然后对不上。
 */
@Tag(name = "Admin")
@SecurityRequirement(name = "adminSession")
@RestController
@RequestMapping("/admin/api/pages")
public class SiteTextController {

    private static final Logger log = LoggerFactory.getLogger(SiteTextController.class);

    private final SiteTextService service;

    public SiteTextController(SiteTextService service) {
        this.service = service;
    }

    /** 按页面分组的全部文案块（含当前生效值与默认值） */
    @GetMapping("")
    public Map<String, Object> list() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("pages", service.grouped());
        out.put("pageLabels", service.pageLabels());
        return out;
    }

    /**
     * 保存一块文案。
     *
     * <p>body：{@code {page, key, text}}。{@code text} 传空串 = 恢复默认。
     */
    @PostMapping("")
    public ResponseEntity<?> save(@RequestBody SaveTextRequest body) {
        String page = trim(body.page());
        String key = trim(body.key());
        if (page == null || key == null) {
            throw ApiException.badRequest("需要 page 和 key");
        }
        boolean ok;
        try {
            ok = service.save(page, key, body.text() == null ? "" : body.text());
        } catch (Exception e) {
            log.warn("[SITE] 保存文案失败 {}.{}：{}", page, key, e.getMessage());
            throw ApiException.internal(e.getMessage());
        }
        if (!ok) {
            throw ApiException.badRequest("没有这个文案块：" + page + "." + key);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("pages", service.grouped());
        return ResponseEntity.ok(out);
    }

    /** 恢复一块文案的默认值 */
    @PostMapping("/actions/reset")
    public ResponseEntity<?> reset(@RequestBody ResetTextRequest body) {
        String page = trim(body.page());
        String key = trim(body.key());
        if (page == null || key == null) {
            throw ApiException.badRequest("需要 page 和 key");
        }
        boolean ok;
        try {
            ok = service.reset(page, key);
        } catch (Exception e) {
            throw ApiException.internal(e.getMessage());
        }
        if (!ok) {
            throw ApiException.badRequest("没有这个文案块：" + page + "." + key);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("pages", service.grouped());
        return ResponseEntity.ok(out);
    }

    @JsonIgnoreProperties(ignoreUnknown = false)

    public record SaveTextRequest(String page, String key, String text) {
    }

    @JsonIgnoreProperties(ignoreUnknown = false)

    public record ResetTextRequest(String page, String key) {
    }

    private static String trim(String value) {
        if (value == null) {
            return null;
        }
        String s = value.trim();
        return s.isEmpty() ? null : s;
    }
}
