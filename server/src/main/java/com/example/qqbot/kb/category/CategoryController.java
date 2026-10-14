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
 * 分类管理接口（管理后台）。
 *
 * <p>分类 = **一份人工维护的清单**。词条的分块符号里带什么标签，它就归到什么分类；
 * 标签不在清单里就归「未分类」。所以这里只有四个动作：看清单、新增、改名、删除，
 * 外加一个"这个分类下有哪些词条"的穿透。没有自动归类、没有批量改归属。
 */
@RestController
@RequestMapping("/admin/api/kb/categories")
public class CategoryController {

    private final CategoryService service;

    public CategoryController(CategoryService service) {
        this.service = service;
    }

    /** 分类清单 + 每个分类的词条数 + 未分类数 */
    @GetMapping("")
    public Map<String, Object> list() {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (CategoryService.Stat s : service.stats()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", s.name());
            m.put("label", s.label());
            m.put("count", s.count());
            rows.add(m);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("items", rows);
        out.put("uncategorized", service.uncategorizedCount());
        out.put("uncategorizedName", CategoryService.UNCATEGORIZED);
        out.put("total", rows.size());
        out.put("available", service.isAvailable());
        return out;
    }

    /** 新增一个分类。名字就是分块符号里要写的标签值 */
    @PostMapping("/add")
    public ResponseEntity<Map<String, Object>> add(@RequestBody Map<String, Object> body) {
        String name = str(body.get("name"));
        String label = body.get("label") == null ? "" : String.valueOf(body.get("label"));
        if (name == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "分类名不能为空"));
        }
        if (CategoryService.UNCATEGORIZED.equals(name)) {
            return ResponseEntity.badRequest().body(Map.of("error", "「未分类」是系统保留名，不能新增"));
        }
        if (!service.add(name, label)) {
            return ResponseEntity.badRequest().body(Map.of("error", "分类「" + name + "」已存在或存储不可用"));
        }
        return ResponseEntity.ok(Map.of("ok", true, "name", name));
    }

    /** 改分类的显示名（分类名本身是标签，不改） */
    @PostMapping("/update")
    public ResponseEntity<Map<String, Object>> update(@RequestBody Map<String, Object> body) {
        String name = str(body.get("name"));
        String label = body.get("label") == null ? "" : String.valueOf(body.get("label"));
        if (name == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "需要 name"));
        }
        if (!service.rename(name, label)) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "没有这个分类：" + name));
        }
        return ResponseEntity.ok(Map.of("ok", true));
    }

    /** 删除一个分类。词条不会消失，只是回到「未分类」 */
    @PostMapping("/delete")
    public ResponseEntity<Map<String, Object>> delete(@RequestBody Map<String, Object> body) {
        String name = str(body.get("name"));
        if (name == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "需要 name"));
        }
        if (!service.remove(name)) {
            return ResponseEntity.badRequest().body(Map.of("error", "删除失败：" + name));
        }
        return ResponseEntity.ok(Map.of("ok", true, "name", name));
    }

    /**
     * 分类穿透：点开一个分类，看它下面有哪些词条。
     *
     * <p>{@code category} 不传 = 全部；传 {@code 未分类} = 只看没分类的。
     */
    @GetMapping("/entries")
    public Map<String, Object> entries(@RequestParam(required = false) String category,
                                       @RequestParam(required = false) String q,
                                       @RequestParam(defaultValue = "200") int limit,
                                       @RequestParam(defaultValue = "0") int offset) {
        CategoryService.EntryPage page = service.entries(category, q, limit, offset);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total", page.total());
        out.put("items", page.items());
        return out;
    }

    private static String str(Object o) {
        if (o == null) {
            return null;
        }
        String s = String.valueOf(o).trim();
        return s.isEmpty() ? null : s;
    }
}
