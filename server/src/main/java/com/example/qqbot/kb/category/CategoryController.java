package com.example.qqbot.kb.category;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 分类规划接口（管理后台）。
 *
 * <p>设计：一次返回全部原始分类（600 条左右，几十 KB），前端本地筛选 ——
 * 比每次搜索都发请求快得多，实现也简单。
 */
@RestController
@RequestMapping("/admin/api/kb/categories")
public class CategoryController {

    private final CategoryService service;
    private final CategoryStore store;

    public CategoryController(CategoryService service, CategoryStore store) {
        this.service = service;
        this.store = store;
    }

    /** 全部原始分类 + 当前归属 + 大类统计 */
    @GetMapping("")
    public Map<String, Object> list() {
        List<CategoryService.RawItem> items = service.listAll();

        List<Map<String, Object>> rows = new ArrayList<>();
        for (CategoryService.RawItem it : items) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("raw", it.raw());
            m.put("count", it.count());
            m.put("groupKey", it.groupKey());
            m.put("groupLabel", it.groupLabel());
            m.put("labelZh", it.labelZh());
            m.put("custom", it.custom());
            m.put("hidden", it.hidden());
            rows.add(m);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("items", rows);
        out.put("groups", KbGroups.ALL);
        out.put("stats", service.groupStats());
        out.put("customCount", store.count());
        out.put("total", rows.size());
        return out;
    }

    /**
     * 设置一条分类的归属。
     *
     * <p>groupKey 传 {@code __hidden__} 表示隐藏（不给玩家看）。
     * 传空字符串则是恢复自动规则（删掉手动覆盖）。
     */
    @PostMapping("/set")
    public ResponseEntity<Map<String, Object>> set(@RequestBody Map<String, Object> body) {
        String raw = str(body.get("raw"));
        String groupKey = str(body.get("groupKey"));
        String labelZh = body.get("labelZh") == null ? "" : String.valueOf(body.get("labelZh"));

        if (raw == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "需要 raw"));
        }
        if (groupKey == null || groupKey.isBlank()) {
            boolean ok = store.remove(raw);
            return ResponseEntity.ok(Map.of("ok", true, "reset", ok,
                    "autoGroup", KbGroups.autoGroup(raw)));
        }
        if (!isValidGroup(groupKey)) {
            return ResponseEntity.badRequest().body(Map.of("error", "未知大类：" + groupKey));
        }
        boolean ok = store.set(raw, groupKey, labelZh);
        return ok
                ? ResponseEntity.ok(Map.of("ok", true))
                : ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                        .body(Map.of("error", "保存失败"));
    }

    /** 批量设置（选中多个分类一起改） */
    @PostMapping("/batch")
    public ResponseEntity<Map<String, Object>> batch(@RequestBody Map<String, Object> body) {
        Object rawList = body.get("items");
        String groupKey = str(body.get("groupKey"));
        if (!(rawList instanceof List<?> list) || list.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "需要 items 数组"));
        }
        if (groupKey == null || !isValidGroup(groupKey)) {
            return ResponseEntity.badRequest().body(Map.of("error", "需要有效的 groupKey"));
        }

        // 用 setGroupBatch 而不是 setBatch：前端批量只给大类，中文标签是逐条人工填的，
        // 走 setBatch 会把它一起覆盖成空字符串（静默清空，看不出来）。
        List<String> raws = new ArrayList<>();
        for (Object o : list) {
            String raw = str(o);
            if (raw != null) {
                raws.add(raw);
            }
        }
        int n = store.setGroupBatch(raws, groupKey);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", n > 0);
        out.put("count", n);
        return ResponseEntity.ok(out);
    }

    /** 按自动规则批量归类（不动已有的手动设置） */
    @PostMapping("/auto")
    public ResponseEntity<Map<String, Object>> auto() {
        List<CategoryService.RawItem> items = service.listAll();
        List<CategoryStore.Mapping> ms = new ArrayList<>();
        int skipped = 0;
        for (CategoryService.RawItem it : items) {
            if (it.custom()) {
                skipped++;
                continue;
            }
            String auto = KbGroups.autoGroup(it.raw());
            if (!auto.equals(it.groupKey())) {
                ms.add(new CategoryStore.Mapping(it.raw(), auto, ""));
            }
        }
        int n = store.setBatch(ms);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("applied", n);
        out.put("skipped", skipped);
        out.put("message", "已归类 " + n + " 条（跳过 " + skipped + " 条手动设置的）");
        return ResponseEntity.ok(out);
    }

    /** 全部恢复自动规则 */
    @PostMapping("/reset")
    public ResponseEntity<Map<String, Object>> reset() {
        int n = store.clearAll();
        return ResponseEntity.ok(Map.of("ok", true, "cleared", n,
                "message", "已清空 " + n + " 条手动设置，全部回到自动规则"));
    }

    /**
     * 分类穿透：点开一个分类，看它下面具体有哪些条目。
     *
     * <p>前端在分类列表里点「N 条目」，或点大类卡片上的「查看条目」时调这里。
     * {@code raw} 与 {@code group} 二选一：给了 raw 就按原始分类精确匹配，
     * 只给 group 就按大类（含它下面所有原始分类）匹配。
     *
     * <p>返回 {@code total}（命中总数）和 {@code items}（当前页）。默认一次给
     * 200 条 —— 分类面板里绝大多数分类都在这个量级以内，够用；极端大的
     * 分类靠 {@code q} 搜索缩小，而不是靠翻页。
     */
    @GetMapping("/entries")
    public Map<String, Object> entries(@RequestParam(required = false) String raw,
                                       @RequestParam(required = false) String group,
                                       @RequestParam(required = false) String q,
                                       @RequestParam(defaultValue = "200") int limit,
                                       @RequestParam(defaultValue = "0") int offset) {
        CategoryService.EntryPage page = service.entries(raw, group, q, limit, offset);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total", page.total());
        out.put("items", page.items());
        return out;
    }

    private static boolean isValidGroup(String key) {
        if (KbGroups.HIDDEN.equals(key)) {
            return true;
        }
        for (KbGroups.Group g : KbGroups.ALL) {
            if (g.key().equals(key)) {
                return true;
            }
        }
        return false;
    }

    private static String str(Object o) {
        if (o == null) {
            return null;
        }
        String s = String.valueOf(o).trim();
        return s.isEmpty() ? null : s;
    }
}
