package com.example.qqbot.command;

import com.fasterxml.jackson.databind.ObjectMapper;
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
    private final CommandPolicy props;
    private final ActionRegistry actionRegistry;
    /** 只用来把后台传来的参数表 JSON 转成 ParamSpec，不承担别的语义 */
    private final ObjectMapper mapper;

    public CommandController(CommandStore store, VariableRenderer renderer, CommandPolicy props,
                             ActionRegistry actionRegistry, ObjectMapper mapper) {
        this.store = store;
        this.renderer = renderer;
        this.props = props;
        this.actionRegistry = actionRegistry;
        this.mapper = mapper;
    }

    /** 指令列表 */
    @GetMapping("")
    public Map<String, Object> list() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("available", store.isAvailable());
        out.put("commands", store.listAll());
        out.put("rateLimitPerMinute", props.getRateLimitPerMinute());
        out.put("allowUserIds", props.isAllowUserIds());
        return out;
    }

    /** 可用变量（配置界面靠它渲染"点击插入"按钮，变量页靠它列清单） */
    @GetMapping("/variables")
    public Map<String, Object> variables() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("variables", renderer.available());
        // preview = 示例值，current = 真实值。会话相关的变量在管理端取不到，两者都是占位
        out.put("preview", renderer.previewAll());
        out.put("current", renderer.currentValues());
        out.put("allowUserIds", props.isAllowUserIds());
        return out;
    }

    /**
     * 已注册的动作。
     *
     * <p>配置界面用它渲染"绑定哪个执行器"的下拉，以及「载入该动作的参数表」——
     * 后者只是把声明抄进表单，存进库里的仍然是这条命令自己的参数表。
     * 于是管理员有权增删可选参数，而不用等代码改。
     */
    @GetMapping("/actions")
    public Map<String, Object> actions() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (CommandAction action : actionRegistry.all()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", action.id());
            item.put("params", action.params());
            out.add(item);
        }
        return Map.of("actions", out);
    }

    /** 新增或更新 */
    @PostMapping("")
    public ResponseEntity<Map<String, Object>> upsert(@RequestBody Map<String, Object> body) {
        String trigger = str(body.get("trigger"));
        String reply = str(body.get("reply"));

        if (trigger == null || trigger.isBlank()) {
            return bad("触发词不能为空");
        }
        // 命令名就是路径：允许 set/output 这种分层写法，但不允许空格与空段
        String t = trigger.trim();
        if (t.contains(" ") || t.startsWith("/") || t.endsWith("/") || t.contains("//")) {
            return bad("命令路径写法不对：写 set 或 set/output，不要带空格、首尾斜杠或空段");
        }
        for (String seg : t.split("/")) {
            if (!seg.matches("[\\p{IsHan}A-Za-z0-9_.-]+")) {
                return bad("命令路径里的一段「" + seg + "」含不支持的字符");
            }
        }
        // help 是保留字：/路径 /help 要留给单命令帮助
        if (t.equalsIgnoreCase("help") || t.toLowerCase(java.util.Locale.ROOT).endsWith("/help")) {
            return bad("help 是保留字，不能作为命令名或子命令名");
        }

        // 类型与回答模式：只有 agent 类才有"模式"，其余一律回落成 kb（无意义）
        String kind = str(body.get("kind"));
        kind = BotCommand.KIND_AGENT.equalsIgnoreCase(kind == null ? "" : kind) ? BotCommand.KIND_AGENT
                : BotCommand.KIND_ACTION.equalsIgnoreCase(kind == null ? "" : kind)
                ? BotCommand.KIND_ACTION : BotCommand.KIND_TEMPLATE;

        // 动作类命令的"回复"是用法提示，可以为空；话术与问答类必须有内容
        if (!BotCommand.KIND_ACTION.equals(kind) && (reply == null || reply.isBlank())) {
            return bad("回复内容不能为空");
        }

        List<ParamSpec> params = toParams(body.get("params"));
        String paramError = validateParams(params, BotCommand.KIND_ACTION.equals(kind));
        if (paramError != null) {
            return bad(paramError);
        }
        String action = BotCommand.KIND_ACTION.equals(kind) ? str(body.get("action")) : null;
        if (BotCommand.KIND_ACTION.equals(kind) && action == null) {
            return bad("动作类命令要绑定一个执行器");
        }
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
                    mode,
                    params,
                    str(body.get("help")),
                    action));
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

    /**
     * 后台传来的参数表。
     *
     * <p>坏条目直接跳过而不是整单失败：后台表单已经保证形状，
     * 能走到这里说明是手工调用，跳过比 500 更容易排查。
     */
    private List<ParamSpec> toParams(Object raw) {
        List<ParamSpec> out = new ArrayList<>();
        if (raw instanceof List<?> list) {
            for (Object item : list) {
                if (item == null) {
                    continue;
                }
                try {
                    out.add(mapper.convertValue(item, ParamSpec.class));
                } catch (Exception e) {
                    log.warn("[CMD] 忽略一个形状不对的参数条目：{}", item);
                }
            }
        }
        return out;
    }

    /** 参数名必须能写进 {@code #名字-值#}，且不能重名 */
    private static String validateParams(List<ParamSpec> params, boolean actionCommand) {
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (ParamSpec p : params) {
            if (p.name().isEmpty() || !p.name().matches("[\\p{IsHan}A-Za-z0-9_]+")) {
                return "参数名「" + p.name() + "」不合法：只能用汉字、字母、数字、下划线";
            }
            if (!seen.add(p.name())) {
                return "参数名「" + p.name() + "」重复了";
            }
        }
        if (actionCommand && params.isEmpty()) {
            return "动作类命令至少要声明一个参数，否则没有东西可传";
        }
        return null;
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
