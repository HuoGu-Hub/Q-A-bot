package com.example.qqbot.command;

import com.example.qqbot.config.CommandProperties;
import com.example.qqbot.config.KbProperties;
import com.example.qqbot.config.MediaProperties;
import com.example.qqbot.config.QaProperties;
import com.example.qqbot.config.GuardProperties;
import com.example.qqbot.config.LogProperties;
import com.example.qqbot.qa.QaStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 * 指令管理接口（管理后台专用）。
 *
 * <p>认证由 {@link com.example.qqbot.admin.AdminAuthFilter} 统一拦在前面。
 */
@RestController
@RequestMapping("/admin/api/commands")
public class CommandController {

    private static final Logger log = LoggerFactory.getLogger(CommandController.class);

    private final CommandStore store;
    private final VariableRenderer renderer;
    private final CommandProperties props;

    public CommandController(CommandStore store, VariableRenderer renderer, CommandProperties props) {
        this.store = store;
        this.renderer = renderer;
        this.props = props;
    }

    /** 指令列表 */
    @GetMapping("")
    public Map<String, Object> list() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("available", store.isAvailable());
        out.put("commands", store.listAll());
        out.put("requireMention", props.isRequireMention());
        out.put("requireSlash", props.isRequireSlash());
        out.put("rateLimitPerMinute", props.getRateLimitPerMinute());
        out.put("allowUserIds", props.isAllowUserIds());
        return out;
    }

    /** 可用变量（配置界面靠它渲染"点击插入"按钮） */
    @GetMapping("/variables")
    public Map<String, Object> variables() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("variables", renderer.available());
        out.put("preview", renderer.previewAll());
        out.put("allowUserIds", props.isAllowUserIds());
        return out;
    }

    /** 新增或更新 */
    @PostMapping("")
    public ResponseEntity<Map<String, Object>> upsert(@RequestBody Map<String, Object> body) {
        String trigger = str(body.get("trigger"));
        String reply = str(body.get("reply"));

        if (trigger == null || trigger.isBlank()) {
            return bad("触发词不能为空");
        }
        // 触发词不能含空格或斜杠（斜杠是前缀，不该出现在触发词里）
        String t = trigger.trim();
        if (t.contains(" ") || t.contains("/")) {
            return bad("触发词不能含空格或斜杠（比如写 help 而不是 /help）");
        }
        if (reply == null || reply.isBlank()) {
            return bad("回复内容不能为空");
        }

        // 类型与回答模式：只有 agent 类才有"模式"，template 一律回落成 kb（无意义）
        String kind = str(body.get("kind"));
        kind = BotCommand.KIND_AGENT.equalsIgnoreCase(kind == null ? "" : kind)
                ? BotCommand.KIND_AGENT : BotCommand.KIND_TEMPLATE;
        String mode = str(body.get("mode"));
        mode = BotCommand.MODE_NONE.equalsIgnoreCase(mode == null ? "" : mode)
                ? BotCommand.MODE_NONE : BotCommand.MODE_KB;

        try {
            long id = store.upsert(new BotCommand(
                    0, t, reply,
                    str(body.get("description")) == null ? "" : str(body.get("description")),
                    str(body.get("scope")) == null ? "all" : str(body.get("scope")),
                    toLongList(body.get("groupIds")),
                    str(body.get("minRole")) == null ? "member" : str(body.get("minRole")),
                    !Boolean.FALSE.equals(body.get("enabled")),
                    body.get("sortOrder") instanceof Number n ? n.intValue() : 0,
                    false,
                    kind,
                    mode));
            log.info("[CMD] 保存指令 /{}（id={}，类型={}，模式={}）", t, id, kind, mode);
            return ResponseEntity.ok(Map.of("ok", true, "id", id));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", e.getMessage()));
        }
    }

    /** 删除 */
    @PostMapping("/delete")
    public ResponseEntity<Map<String, Object>> delete(@RequestBody Map<String, Object> body) {
        String trigger = str(body.get("trigger"));
        if (trigger == null) {
            return bad("需要 trigger");
        }
        try {
            return store.delete(trigger)
                    ? ResponseEntity.ok(Map.of("ok", true))
                    : ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "没有这条指令"));
        } catch (Exception e) {
            // 内置指令不能删，会走到这里
            return bad(e.getMessage());
        }
    }

    /** 启用/停用 */
    @PostMapping("/toggle")
    public ResponseEntity<Map<String, Object>> toggle(@RequestBody Map<String, Object> body) {
        String trigger = str(body.get("trigger"));
        boolean enabled = !Boolean.FALSE.equals(body.get("enabled"));
        if (trigger == null) {
            return bad("需要 trigger");
        }
        try {
            return store.setEnabled(trigger, enabled)
                    ? ResponseEntity.ok(Map.of("ok", true, "enabled", enabled))
                    : ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "没有这条指令"));
        } catch (Exception e) {
            return bad(e.getMessage());
        }
    }

    /**
     * 预览渲染结果 —— 配置界面的"实时预览"靠它。
     *
     * @param template 模板内容
     * @param real     true=用真实数据渲染；false=用示例值（默认）
     */
    @PostMapping("/preview")
    public Map<String, Object> preview(@RequestBody Map<String, Object> body) {
        String template = str(body.get("template"));
        boolean real = Boolean.TRUE.equals(body.get("real"));
        // 预览也支持命令参数：配置界面填一个示例参数，就能看到 {args} 渲染成什么
        String args = str(body.get("args"));
        String rendered = renderer.render(template == null ? "" : template, null, !real,
                args == null ? "" : args);
        return Map.of("rendered", rendered, "length", rendered.length());
    }

    // ==================== C6：使用统计 ====================

    @GetMapping("/stats")
    public Map<String, Object> stats(@RequestParam(defaultValue = "30") int days) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("topUsed", store.topUsed(days, 20));
        // ★ 未配置的命令 —— 直接告诉你该加什么
        out.put("unmatched", store.unmatched(days, 20));
        out.put("total", store.count());
        return out;
    }

    // ==================== 内部 ====================

    private static ResponseEntity<Map<String, Object>> bad(String msg) {
        return ResponseEntity.badRequest().body(Map.of("error", msg));
    }

    private static String str(Object o) {
        if (o == null) {
            return null;
        }
        String s = String.valueOf(o).trim();
        return s.isEmpty() ? null : s;
    }

    private static List<Long> toLongList(Object o) {
        List<Long> out = new ArrayList<>();
        if (o instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Number n) {
                    out.add(n.longValue());
                } else if (item != null) {
                    try {
                        out.add(Long.parseLong(String.valueOf(item).trim()));
                    } catch (NumberFormatException ignored) {
                        // 忽略非数字
                    }
                }
            }
        }
        return out;
    }
}
