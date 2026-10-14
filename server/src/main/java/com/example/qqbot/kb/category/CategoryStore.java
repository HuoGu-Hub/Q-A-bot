package com.example.qqbot.kb.category;

import com.example.qqbot.persistence.KbCategoryRepository;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 分类目录的存储 —— 人工维护的分类清单。
 *
 * <p>只有一份数据：分类名（= 分块符号里的标签值）与显示名。没有自动归类规则，
 * 没有"原始标签 → 大类"的映射，所以这里没有可以漂移的第二份真源。
 */
@Component
public class CategoryStore {

    private static final Logger log = LoggerFactory.getLogger(CategoryStore.class);

    /** 数据访问全部委托给它 —— SQL 与 java.sql 都在 persistence */
    private final KbCategoryRepository repo;
    private volatile boolean available;

    public CategoryStore(KbCategoryRepository repo) {
        this.repo = repo;
    }

    /** 一个分类 */
    public record Entry(String name, String label) {
    }

    @PostConstruct
    public void init() {
        if (!repo.isAvailable()) {
            log.warn("[KB-CAT] 问答库不可用，分类功能关闭");
            return;
        }
        try {
            repo.initSchema();
            available = true;
            log.info("[KB-CAT] 分类目录就绪：{} 个分类", repo.count());
        } catch (Exception e) {
            log.warn("[KB-CAT] 初始化失败：{}", e.getMessage());
            available = false;
        }
    }

    public boolean isAvailable() {
        return available && repo.isAvailable();
    }

    /** 全部分类，按 sort、name 排序 */
    public List<Entry> all() {
        if (!available) {
            return List.of();
        }
        try {
            List<Entry> out = new ArrayList<>();
            for (KbCategoryRepository.Row r : repo.all()) {
                out.add(new Entry(r.name(), r.label() == null ? "" : r.label()));
            }
            return out;
        } catch (Exception e) {
            log.warn("[KB-CAT] 读取失败：{}", e.getMessage());
            return List.of();
        }
    }

    /** 分类名 → 显示名。显示名留空时用分类名 */
    public Map<String, String> labels() {
        Map<String, String> out = new LinkedHashMap<>();
        for (Entry e : all()) {
            out.put(e.name(), e.label().isBlank() ? e.name() : e.label());
        }
        return out;
    }

    /** 新增或更新一个分类（不改变已有条目的归属） */
    public boolean upsert(String name, String label) {
        if (!available || name == null || name.isBlank()) {
            return false;
        }
        try {
            String key = name.trim();
            // 已存在的保留原有顺序，新增的追加到末尾
            KbCategoryRepository.Row cur = repo.find(key);
            int sort = cur == null ? (int) repo.count() : cur.sort();
            repo.upsert(key, label == null ? "" : label.trim(), sort, Instant.now().toString());
            return true;
        } catch (Exception e) {
            log.warn("[KB-CAT] 保存失败：{}", e.getMessage());
            return false;
        }
    }

    /** 删掉一个分类（词条不会消失，只是回到「未分类」） */
    public boolean remove(String name) {
        if (!available || name == null || name.isBlank()) {
            return false;
        }
        try {
            return repo.delete(name.trim());
        } catch (Exception e) {
            log.warn("[KB-CAT] 删除失败：{}", e.getMessage());
            return false;
        }
    }

    public long count() {
        return available ? repo.count() : 0;
    }
}
