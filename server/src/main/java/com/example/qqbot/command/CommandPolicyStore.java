package com.example.qqbot.command;

import com.example.qqbot.persistence.CommandPolicyRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 数据策略的业务侧。
 *
 * <p><b>没有行就是默认</b>（只看本群）。这条规则让老库升级不需要迁移，
 * 也让"新建命令默认隔离"变成天然行为而不是每个调用点记得去设。
 */
@Component
public class CommandPolicyStore {

    private static final Logger log = LoggerFactory.getLogger(CommandPolicyStore.class);

    private final CommandPolicyRepository repo;
    private final ObjectMapper mapper;

    private volatile boolean available;

    public CommandPolicyStore(CommandPolicyRepository repo, ObjectMapper mapper) {
        this.repo = repo;
        this.mapper = mapper;
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
            log.info("[POLICY] 数据策略库就绪");
        } catch (Exception e) {
            log.warn("[POLICY] 初始化失败，所有命令按「只看本群」处理：{}", e.getMessage());
            available = false;
        }
    }

    public boolean isAvailable() {
        return available;
    }

    /** 取不到就返回默认 —— 调用方永远不用判 null */
    public DataPolicy of(long commandId) {
        if (!available || commandId <= 0) {
            return DataPolicy.DEFAULT;
        }
        CommandPolicyRepository.Row row = repo.find(commandId);
        if (row == null) {
            return DataPolicy.DEFAULT;
        }
        return new DataPolicy(parseScope(row.scope()), parseGroups(row.groupsJson()),
                row.showGroup(), row.showUser(), row.allowPrivate());
    }

    public void save(long commandId, DataPolicy policy) {
        if (!available || commandId <= 0 || policy == null) {
            return;
        }
        repo.save(commandId, new CommandPolicyRepository.Row(policy.scope().name(),
                toJson(policy.groups()), policy.showGroup(), policy.showUser(),
                policy.allowPrivate()));
    }

    public void delete(long commandId) {
        if (available && commandId > 0) {
            repo.delete(commandId);
        }
    }

    private static DataPolicy.DataScope parseScope(String raw) {
        try {
            return DataPolicy.DataScope.valueOf(raw == null ? "GROUP" : raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return DataPolicy.DataScope.GROUP;
        }
    }

    private List<Long> parseGroups(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return mapper.readValue(json, new TypeReference<List<Long>>() { });
        } catch (Exception e) {
            return List.of();
        }
    }

    private String toJson(List<Long> groups) {
        try {
            return mapper.writeValueAsString(groups == null ? List.of() : groups);
        } catch (Exception e) {
            return "[]";
        }
    }
}
