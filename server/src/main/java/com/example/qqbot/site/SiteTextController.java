package com.example.qqbot.site;

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
    public ResponseEntity<?> save(@RequestBody Map<String, Object> body) {
        String page = str(body.get("page"));
        String key = str(body.get("key"));
        Object text = body.get("text");
        if (page == null || key == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "需要 page 和 key"));
        }
        try {
            boolean ok = service.save(page, key, text == null ? "" : String.valueOf(text));
            if (!ok) {
                return ResponseEntity.badRequest()
                        .body(Map.of("error", "没有这个文案块：" + page + "." + key));
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ok", true);
            out.put("pages", service.grouped());
            return ResponseEntity.ok(out);
        } catch (Exception e) {
            log.warn("[SITE] 保存文案失败 {}.{}：{}", page, key, e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", e.getMessage()));
        }
    }

    /** 恢复一块文案的默认值 */
    @PostMapping("/reset")
    public ResponseEntity<?> reset(@RequestBody Map<String, Object> body) {
        String page = str(body.get("page"));
        String key = str(body.get("key"));
        if (page == null || key == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "需要 page 和 key"));
        }
        try {
            if (!service.reset(page, key)) {
                return ResponseEntity.badRequest()
                        .body(Map.of("error", "没有这个文案块：" + page + "." + key));
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ok", true);
            out.put("pages", service.grouped());
            return ResponseEntity.ok(out);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", e.getMessage()));
        }
    }

    private static String str(Object o) {
        if (o == null) {
            return null;
        }
        String s = String.valueOf(o).trim();
        return s.isEmpty() ? null : s;
    }
}
