package com.example.qqbot.kb.wiki;

import com.example.qqbot.persistence.KbWikiSourceRepository;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/**
 * wiki 拉取范围 —— 管理端页面上勾出来的那份清单。
 *
 * <p>语义只有两条：
 * <ol>
 *   <li><b>表里有行就用表里的</b>（只取 {@code enabled} 的）；</li>
 *   <li><b>表里一行都没有就用 yml 的默认值</b> —— 老部署不用手动初始化。</li>
 * </ol>
 *
 * <p>所以 yml 的角色从"唯一真源"变成了"出厂默认"。运维在页面上保存过一次之后，
 * 范围就完全由页面决定。
 */
@Component
public class KbWikiSourceStore {

    private static final Logger log = LoggerFactory.getLogger(KbWikiSourceStore.class);

    private final KbWikiSourceRepository repo;
    private volatile boolean available;

    public KbWikiSourceStore(KbWikiSourceRepository repo) {
        this.repo = repo;
    }

    @PostConstruct
    public void init() {
        if (!repo.isAvailable()) {
            log.warn("[KB-WIKI] 问答库不可用，拉取来源存储一并关闭（回落到 yml 默认值）");
            return;
        }
        try {
            repo.initSchema();
            available = true;
            log.info("[KB-WIKI] 拉取来源就绪：{} 条", repo.count());
        } catch (Exception e) {
            log.warn("[KB-WIKI] 拉取来源初始化失败：{}", e.getMessage());
        }
    }

    public boolean isAvailable() {
        return available && repo.isAvailable();
    }

    /** 表里的全部来源（含停用的），管理端页面用它回显 */
    public List<KbWikiSourceRepository.Source> all() {
        return available ? repo.list() : List.of();
    }

    /** 表里启用的来源；**空表示"没配置过"，调用方应回落到 yml 默认值** */
    public List<KbWikiSourceRepository.Source> enabled() {
        return all().stream().filter(KbWikiSourceRepository.Source::enabled).toList();
    }

    public int count() {
        return available ? repo.count() : 0;
    }

    /** 整表替换（页面上点保存） */
    public void replace(List<KbWikiSourceRepository.Source> items) {
        if (!available) {
            return;
        }
        String now = Instant.now().toString();
        repo.replace(items.stream()
                .map(s -> new KbWikiSourceRepository.Source(s.name().strip(), s.kind(), s.enabled(), now))
                .filter(s -> !s.name().isEmpty())
                .toList());
        log.info("[KB-WIKI] 拉取来源已更新：{} 条（启用 {} 条）",
                count(), enabled().size());
    }
}
