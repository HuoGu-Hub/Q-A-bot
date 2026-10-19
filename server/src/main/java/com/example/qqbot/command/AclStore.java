package com.example.qqbot.command;

import com.example.qqbot.persistence.AclRepository;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 名单的业务侧：谁能用哪条命令。
 *
 * <p>不缓存。名单是几十行的量级，查一次 SQLite 零点几毫秒，
 * 比维护"改完之后缓存什么时候失效"划算，而且天然即改即生效 ——
 * 权限这东西改完不生效是最容易出事的。
 */
@Component
public class AclStore {

    private static final Logger log = LoggerFactory.getLogger(AclStore.class);

    private final AclRepository repo;

    private volatile boolean available;

    public AclStore(AclRepository repo) {
        this.repo = repo;
    }

    @PostConstruct
    void init() {
        try {
            if (!repo.isAvailable()) {
                available = false;
                return;
            }
            repo.initSchema();
            available = true;
            log.info("[ACL] 名单库就绪");
        } catch (Exception e) {
            log.warn("[ACL] 初始化名单库失败，权限退回只按档位判定：{}", e.getMessage());
            available = false;
        }
    }

    public boolean isAvailable() {
        return available;
    }

    /** 这条命令自己的名单；没有就是空表 */
    public List<AclEntry> ofCommand(long commandId) {
        return available && commandId > 0 ? repo.list(commandId) : List.of();
    }

    /** 全局名单：对所有命令生效 */
    public List<AclEntry> global() {
        return available ? repo.list(AclEntry.GLOBAL) : List.of();
    }

    public void replaceCommand(long commandId, List<AclEntry> entries) {
        if (available && commandId > 0) {
            repo.replace(commandId, entries);
        }
    }

    public void replaceGlobal(List<AclEntry> entries) {
        if (available) {
            repo.replace(AclEntry.GLOBAL, entries);
        }
    }

    /** 命令被删掉时连带清理 */
    public void deleteCommand(long commandId) {
        if (available && commandId > 0) {
            repo.delete(commandId);
        }
    }
}
