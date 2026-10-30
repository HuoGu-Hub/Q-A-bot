package com.example.qqbot.settings;

import io.swagger.v3.oas.annotations.security.SecurityRequirement;

import io.swagger.v3.oas.annotations.tags.Tag;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import com.example.qqbot.web.ApiException;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 配置中心接口（管理后台专用）。
 *
 * <p>只暴露 SettingsWhitelist 里允许改的项 —— 其余**根本不显示**，
 * 接口也拒绝写入。
 */
@Tag(name = "Admin")
@SecurityRequirement(name = "adminSession")
@RestController
@RequestMapping("/admin/api/settings")
public class SettingsController {

    private final SettingsService service;

    public SettingsController(SettingsService service) {
        this.service = service;
    }

    /** 当前可写配置（按分组） */
    @GetMapping("")
    public Map<String, Object> current() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("groups", service.current());
        out.put("applyCount", service.applyCount());
        out.put("note", "这些都是 Bot 行为配置，保存后立即生效，不用重启");
        return out;
    }

    /** 保存（热生效 + 持久化） */
    @PostMapping("")
    public Map<String, Object> save(@RequestBody SaveSettingsRequest body) {
        if (body.changes() == null) {
            throw ApiException.badRequest("需要 changes 字段");
        }
        Map<String, Object> normalized = new LinkedHashMap<>();
        body.changes().forEach((k, v) -> normalized.put(String.valueOf(k), v));
        return service.apply(normalized);
    }

    @JsonIgnoreProperties(ignoreUnknown = false)

    public record SaveSettingsRequest(Map<String, Object> changes) {
    }
}
