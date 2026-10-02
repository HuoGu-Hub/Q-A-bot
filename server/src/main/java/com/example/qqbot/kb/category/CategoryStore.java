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
 * 分类映射的存储：把 Wiki 的原始分类映射到玩家视角的大类。

 * <p><b>只有被管理员改过的分类才存库</b> —— 没存过的走 {@link KbGroups#autoGroup} 自动规则。
 * 这样：
 * <ul>
 *   <li>冷启动时零配置就能用（自动规则打底）</li>
 *   <li>只存差异，改一条存一条</li>
 *   <li>规则升级后，没被手动改过的分类会自动跟着变</li>
 * </ul>
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

    /** 一条映射：原始分类名 -> 大类 + 中文标签 */
    public record Mapping(String raw, String groupKey, String labelZh) {
    }

    @PostConstruct
    public void init() {
        if (!repo.isAvailable()) {
            log.warn("[KB-CAT] 问答库不可用，分类映射功能关闭");
            return;
        }
        try {
            repo.initSchema();
            available = true;
            log.info("[KB-CAT] 分类映射就绪：{} 条", repo.count());
        } catch (Exception e) {
            log.warn("[KB-CAT] 初始化失败：{}", e.getMessage());
            available = false;
        }
    }

    public boolean isAvailable() {
        return available && repo.isAvailable();
    }

    public Map<String, Mapping> loadAll() {
        Map<String, Mapping> out = new LinkedHashMap<>();
        if (!available) {
            return out;
        }
        try {
            for (KbCategoryRepository.Row r : repo.all()) {
                out.put(r.raw(), new Mapping(r.raw(), r.groupKey(), r.labelZh()));
            }
        } catch (Exception e) {
            log.warn("[KB-CAT] 读取失败：{}", e.getMessage());
        }
        return out;
    }

    /** 设置一条（upsert）—— 会覆盖中文标签 */
    public boolean set(String raw, String groupKey, String labelZh) {
        if (!available || raw == null || raw.isBlank()) {
            return false;
        }
        try {
            repo.upsert(raw, groupKey, labelZh, Instant.now().toString());
            return true;
        } catch (Exception e) {
            log.warn("[KB-CAT] 保存失败：{}", e.getMessage());
            return false;
        }
    }

    /**
     * 批量只改大类，**保留已有中文标签**（一个事务）。
     *
     * <p>为什么不能复用 {@link #set}：那会把 {@code label_zh} 一起覆盖成空。
     * 批量改归属时前端只知道大类（中文标签是逐条人工填的），
     * 复用就等于把选中的几十条中文标签一次性清空 —— 静默的数据丢失，
     * 而且看不出来（列表上没标签的行本来也长这样）。
     * SQL 层的区分见 {@link KbCategoryRepository}。
     */
    public int setGroupBatch(List<String> raws, String groupKey) {
        if (!available || raws == null || raws.isEmpty()) {
            return 0;
        }
        try {
            return repo.upsertGroupOnly(raws, groupKey, Instant.now().toString());
        } catch (Exception e) {
            log.warn("[KB-CAT] 批量改归属失败：{}", e.getMessage());
            return 0;
        }
    }

    /** 批量设置（一个事务） */
    public int setBatch(List<Mapping> items) {
        if (!available || items == null || items.isEmpty()) {
            return 0;
        }
        try {
            List<KbCategoryRepository.Row> rows = items.stream()
                    .map(m -> new KbCategoryRepository.Row(m.raw(), m.groupKey(), m.labelZh()))
                    .toList();
            return repo.upsertBatch(rows, Instant.now().toString());
        } catch (Exception e) {
            log.warn("[KB-CAT] 批量保存失败：{}", e.getMessage());
            return 0;
        }
    }

    /** 删掉一条映射（恢复成自动规则） */
    public boolean remove(String raw) {
        if (!available) {
            return false;
        }
        try {
            return repo.delete(raw);
        } catch (Exception e) {
            return false;
        }
    }

    /** 全部清空（恢复成纯自动规则） */
    public int clearAll() {
        if (!available) {
            return 0;
        }
        try {
            return repo.clearAll();
        } catch (Exception e) {
            return 0;
        }
    }

    public long count() {
        return available ? repo.count() : 0;
    }
}
