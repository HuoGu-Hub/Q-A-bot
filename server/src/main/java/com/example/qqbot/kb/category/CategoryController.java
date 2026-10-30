package com.example.qqbot.kb.category;

import com.example.qqbot.web.ApiException;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 分类管理接口（管理后台）。
 *
 * <p>分类 = **一份人工维护的清单**。词条的分块符号里带什么标签，它就归到什么分类；
 * 标签不在清单里就归「未分类」。所以这里只有四个动作：看清单、新增、改名、删除，
 * 外加一个"这个分类下有哪些词条"的穿透。没有自动归类、没有批量改归属。
 */
@Tag(name = "Admin")
@SecurityRequirement(name = "adminSession")
@RestController
@RequestMapping("/admin/api/kb/categories")
public class CategoryController {

    private final CategoryService service;

    public CategoryController(CategoryService service) {
        this.service = service;
    }

    /** 分类清单 + 每个分类的词条数 + 未分类数 */
    @GetMapping("")
    public CategoryList list() {
        List<CategoryService.Stat> items = service.stats();
        return new CategoryList(items, service.uncategorizedCount(),
                CategoryService.UNCATEGORIZED, items.size(), service.isAvailable());
    }

    /** 新增一个分类。名字就是分块符号里要写的标签值 */
    @PostMapping("")
    public ResponseEntity<Void> add(@RequestBody CategoryRequest body) {
        String name = trim(body.name());
        String label = body.label() == null ? "" : body.label();
        if (name == null) {
            throw ApiException.badRequest("分类名不能为空");
        }
        if (CategoryService.UNCATEGORIZED.equals(name)) {
            throw ApiException.badRequest("「未分类」是系统保留名，不能新增");
        }
        if (!service.add(name, label)) {
            throw ApiException.badRequest("分类「" + name + "」已存在或存储不可用");
        }
        return ResponseEntity.noContent().build();
    }

    @PatchMapping("/{name}")
    public ResponseEntity<Void> update(@PathVariable String name,
                                       @RequestBody CategoryRequest body) {
        String label = body.label() == null ? "" : body.label();
        if (!service.rename(name, label)) {
            throw ApiException.notFound("没有这个分类：" + name);
        }
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{name}")
    public ResponseEntity<Void> delete(@PathVariable String name) {
        if (!service.remove(name)) {
            throw ApiException.badRequest("删除失败：" + name);
        }
        return ResponseEntity.noContent().build();
    }

    /**
     * 分类穿透：点开一个分类，看它下面有哪些词条。
     *
     * <p>{@code category} 不传 = 全部；传 {@code 未分类} = 只看没分类的。
     */
    @GetMapping("/entries")
    public CategoryService.EntryPage entries(@RequestParam(required = false) String category,
                                             @RequestParam(required = false) String q,
                                             @RequestParam(defaultValue = "200") int limit,
                                             @RequestParam(defaultValue = "0") int offset) {
        return service.entries(category, q, limit, offset);
    }

    private static String trim(String value) {
        if (value == null) {
            return null;
        }
        String s = value.trim();
        return s.isEmpty() ? null : s;
    }

    public record CategoryList(List<CategoryService.Stat> items, int uncategorized,
                               String uncategorizedName, int total, boolean available) {
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record CategoryRequest(String name, String label) {
    }
}
