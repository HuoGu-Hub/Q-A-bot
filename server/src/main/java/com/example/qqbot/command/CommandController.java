package com.example.qqbot.command;

import io.swagger.v3.oas.annotations.security.SecurityRequirement;

import io.swagger.v3.oas.annotations.tags.Tag;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import com.example.qqbot.persistence.CommandRepository;
import com.example.qqbot.web.ApiException;

import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 指令管理接口（管理后台专用）。
 *
 * <p>认证由 {@link com.example.qqbot.admin.AdminAuthFilter} 统一拦在前面。
 */
@Tag(name = "Admin")
@SecurityRequirement(name = "adminSession")
@RestController
@RequestMapping("/admin/api/commands")
public class CommandController {

    private static final Logger log = LoggerFactory.getLogger(CommandController.class);

    private final CommandStore store;
    private final VariableRenderer renderer;
    private final CommandPolicy props;
    private final ActionRegistry actionRegistry;
    private final CommandPolicyStore policyStore;
    private final CommandCatalog catalog;
    private final BoardStore boards;
    private final CommandHelp commandHelp;

    public CommandController(CommandStore store, VariableRenderer renderer, CommandPolicy props,
                             ActionRegistry actionRegistry, CommandPolicyStore policyStore,
                             CommandCatalog catalog, BoardStore boards, CommandHelp commandHelp) {
        this.store = store;
        this.renderer = renderer;
        this.props = props;
        this.actionRegistry = actionRegistry;
        this.policyStore = policyStore;
        this.catalog = catalog;
        this.boards = boards;
        this.commandHelp = commandHelp;
    }

    /** 指令列表 */
    @GetMapping("")
    public CommandList list() {
        List<CommandView> views = new ArrayList<>();
        for (BotCommand command : store.listAll()) {
            views.add(CommandView.of(command, commandHelp));
        }
        return new CommandList(store.isAvailable(), views,
                props.getRateLimitPerMinute(), props.isAllowUserIds());
    }

    /**
     * 全部命令目录：板块 + 指令。
     *
     * <p>后台的列表与群里的 {@code /list} 读同一份条目 —— 这里是"两边看到的东西
     * 必须一样"的落点。{@code includeDisabled=true} 供后台显示停用项。
     */
    @GetMapping("/catalog")
    public CatalogEntries catalog(@RequestParam(defaultValue = "true") boolean includeDisabled) {
        return new CatalogEntries(catalog.entries(includeDisabled));
    }

    /**
     * 所有命令的数据策略。
     *
     * <p>单独一条而不是塞进指令列表：策略是后加的维度，老前端不认这个字段也不该因此坏掉；
     * 权限总览页把两条响应按 id 合起来看。
     */
    @GetMapping("/policies")
    public CommandPolicies policies() {
        List<CommandPolicyView> out = new ArrayList<>();
        for (BotCommand cmd : store.listAll()) {
            DataPolicy policy = policyStore.of(cmd.id());
            out.add(new CommandPolicyView(cmd.id(), policy.scope().name(), policy.groups(),
                    policy.showGroup(), policy.showUser(), policy.allowPrivate()));
        }
        return new CommandPolicies(out);
    }

    /** 可用变量（配置界面靠它渲染"点击插入"按钮，变量页靠它列清单） */
    @GetMapping("/variables")
    public Variables variables() {
        // preview = 示例值，current = 真实值。会话相关的变量在管理端取不到，两者都是占位
        return new Variables(renderer.available(), renderer.previewAll(),
                renderer.currentValues(), props.isAllowUserIds());
    }

    /**
     * 已注册的动作。
     *
     * <p>配置界面用它渲染"绑定哪个执行器"的下拉，以及「载入该动作的参数表」——
     * 后者只是把声明抄进表单，存进库里的仍然是这条命令自己的参数表。
     * 于是管理员有权增删可选参数，而不用等代码改。
     */
    @GetMapping("/actions")
    public Actions actions() {
        List<ActionView> out = new ArrayList<>();
        for (CommandAction action : actionRegistry.all()) {
            out.add(new ActionView(action.id(), action.label(), action.description(),
                    action.defaultPath(), action.params()));
        }
        return new Actions(out);
    }

    /** 新增或更新 */
    @PostMapping("")
    public ResponseEntity<SavedCommand> upsert(@Valid @RequestBody UpsertCommandRequest body) {
        String trigger = trimToNull(body.trigger());
        String reply = trimToNull(body.reply());

        if (trigger == null) {
            throw ApiException.badRequest("触发词不能为空");
        }
        String t = trigger;
        if (t.contains(" ") || t.startsWith("/") || t.endsWith("/") || t.contains("//")) {
            throw ApiException.badRequest("命令路径写法不对：写 set 或 set/output，不要带空格、首尾斜杠或空段");
        }
        for (String seg : t.split("/")) {
            if (!seg.matches("[\\p{IsHan}A-Za-z0-9_.-]+")) {
                throw ApiException.badRequest("命令路径里的一段「" + seg + "」含不支持的字符");
            }
        }
        String first = t.split("/")[0];
        if ("help".equalsIgnoreCase(first) || "list".equalsIgnoreCase(first)
                || "create".equalsIgnoreCase(first) || "delete".equalsIgnoreCase(first)
                || "prompt".equalsIgnoreCase(first)) {
            throw ApiException.badRequest("help / list / create / delete / prompt 由系统或板块动作接管，不能作为指令路径的第一段");
        }
        if (t.toLowerCase(java.util.Locale.ROOT).endsWith("/help")) {
            throw ApiException.badRequest("help 是保留字，不能作为子命令名");
        }
        var shadowing = boards.find(first);
        if (shadowing != null && shadowing.enabled()) {
            throw ApiException.badRequest("「" + first + "」是板块名，指令路径不能和板块重名");
        }

        String kind = body.kind();
        kind = BotCommand.KIND_AGENT.equalsIgnoreCase(kind == null ? "" : kind) ? BotCommand.KIND_AGENT
                : BotCommand.KIND_ACTION.equalsIgnoreCase(kind == null ? "" : kind)
                ? BotCommand.KIND_ACTION : BotCommand.KIND_TEMPLATE;

        if (!BotCommand.KIND_ACTION.equals(kind) && (reply == null || reply.isBlank())) {
            throw ApiException.badRequest("回复内容不能为空");
        }

        List<ParamSpec> params = body.params() == null ? List.of() : body.params();
        String paramError = validateParams(params, BotCommand.KIND_ACTION.equals(kind));
        if (paramError != null) {
            throw ApiException.badRequest(paramError);
        }
        String action = BotCommand.KIND_ACTION.equals(kind) ? trimToNull(body.action()) : null;
        if (BotCommand.KIND_ACTION.equals(kind) && action == null) {
            throw ApiException.badRequest("动作类命令要绑定一个执行器");
        }
        String mode = body.mode();
        mode = BotCommand.MODE_NONE.equalsIgnoreCase(mode == null ? "" : mode)
                ? BotCommand.MODE_NONE : BotCommand.MODE_KB;

        BotCommand cmd = new BotCommand(
                0, t, reply,
                body.description() == null ? "" : body.description(),
                body.scope() == null ? "all" : body.scope(),
                body.groupIds() == null ? List.of() : body.groupIds(),
                body.minRole() == null ? "member" : body.minRole(),
                body.enabled() == null || body.enabled(),
                body.sortOrder() == null ? 0 : body.sortOrder(),
                false,
                kind,
                mode,
                params,
                trimToNull(body.help()),
                trimToNull(body.listReply()),
                action);

        Long id = body.id();
        try {
            long savedId;
            if (id != null && id > 0) {
                BotCommand existing = store.findById(id);
                if (existing == null) {
                    throw ApiException.badRequest("指令不存在：id=" + id);
                }
                if (existing.builtin() && !existing.trigger().equalsIgnoreCase(t)) {
                    throw ApiException.badRequest("内置指令不能改名");
                }
                BotCommand sameName = store.findByTrigger(t);
                if (sameName != null && sameName.id() != id.longValue()) {
                    throw ApiException.badRequest("命令名 /" + t + " 已经被占用");
                }
                savedId = store.saveExisting(id, cmd);
                if (!existing.trigger().equalsIgnoreCase(t)) {
                    store.renameUsage(existing.trigger(), t);
                }
            } else {
                if (store.findByTrigger(t) != null) {
                    throw ApiException.badRequest("命令名 /" + t + " 已经存在；要修改请从列表里点编辑");
                }
                savedId = store.upsert(cmd);
            }
            log.info("[CMD] 保存指令 /{}（id={}，类型={}，模式={}）", t, savedId, kind, mode);
            return ResponseEntity.ok(new SavedCommand(savedId));
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw ApiException.internal(e.getMessage());
        }
    }

    /** 删除 */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable long id) {
        BotCommand cmd = store.findById(id);
        if (cmd == null) {
            throw ApiException.notFound("没有这条指令");
        }
        try {
            if (!store.delete(cmd.trigger())) {
                throw ApiException.notFound("没有这条指令");
            }
            return ResponseEntity.noContent().build();
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw ApiException.badRequest(e.getMessage());
        }
    }

    /** 只改准入档位：/prompt 的编辑弹窗用，避免走通用命令表单 */
    @PostMapping("/{id}/access")
    public ResponseEntity<Void> access(@PathVariable long id,
                                                      @RequestBody AccessRequest body) {
        String access = trimToNull(body.access());
        try {
            if (!store.setAccess(id, access)) {
                throw ApiException.notFound("没有这条指令");
            }
            return ResponseEntity.noContent().build();
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw ApiException.badRequest(e.getMessage());
        }
    }

    /** 启用/停用 */
    @PatchMapping("/{id}")
    public ResponseEntity<Void> toggle(@PathVariable long id,
                                                      @RequestBody ToggleCommandRequest body) {
        BotCommand cmd = store.findById(id);
        if (cmd == null) {
            throw ApiException.notFound("没有这条指令");
        }
        boolean enabled = body.enabled() == null ? !cmd.enabled() : body.enabled();
        try {
            if (!store.setEnabled(cmd.trigger(), enabled)) {
                throw ApiException.notFound("没有这条指令");
            }
            return ResponseEntity.noContent().build();
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw ApiException.badRequest(e.getMessage());
        }
    }

    /**
     * 预览渲染结果 —— 配置界面的"实时预览"靠它。
     */
    @PostMapping("/actions/preview")
    public PreviewResult preview(@RequestBody PreviewRequest body) {
        String template = body.template();
        boolean real = Boolean.TRUE.equals(body.real());
        String args = body.args();
        List<ParamSpec> params = body.params() == null ? List.of() : body.params();

        String rendered;
        if (real) {
            rendered = renderer.render(template == null ? "" : template, null, false,
                    args == null ? "" : args);
        } else {
            Map<String, String> named = new LinkedHashMap<>();
            for (ParamSpec param : params) {
                String sample = param.hasDefault() ? param.defaultValue()
                        : (param.example() != null && !param.example().isBlank()
                        ? param.example() : param.name());
                named.put(param.name(), sample);
            }
            rendered = renderer.render(template == null ? "" : template,
                    RenderContext.preview(args == null ? "" : args, named));
        }
        return new PreviewResult(rendered, rendered.length());
    }

    /**
     * 指令的对外形状。
     *
     * <p>不直接序列化 {@link BotCommand}：它同时有 {@code action} 字段与
     * {@code isAction()} 方法，Jackson 会把后者也当成名为 action 的属性，
     * 结果是绑定的动作 id 被一个 boolean 顶掉 —— 后台既看不到也存不回。
     * 这里显式列出要给的字段，顺便把老值 {@code member} 归一成 {@code all}。
     */
    public record CommandView(long id, String trigger, String reply, String description,
                              String scope, List<Long> groupIds, String access,
                              boolean enabled, int sortOrder, boolean builtin,
                              String kind, String mode, List<ParamSpec> params,
                              String help, String listReply, String action, String usage,
                              String defaultList, String defaultHelp) {

        static CommandView of(BotCommand command) {
            return of(command, new CommandHelp(null));
        }

        static CommandView of(BotCommand command, CommandHelp commandHelp) {
            return new CommandView(
                    command.id(), command.trigger(), command.reply(), command.description(),
                    command.scope(), command.groupIds(),
                    CommandAccess.of(command.access()).code(), command.enabled(),
                    command.sortOrder(), command.builtin(), command.kindName(),
                    command.modeName(), command.params(), command.help(), command.listReply(),
                    command.action(), command.display(),
                    commandHelp.defaultList(command), commandHelp.defaultHelp(command));
        }
    }

    /** 只改单命令的 list/help 回复，prompt 这类独立编辑器也能复用 */
    @PostMapping("/{id}/texts")
    public ResponseEntity<Void> texts(@PathVariable long id,
                                                     @RequestBody TextsRequest body) {
        BotCommand cmd = store.findById(id);
        if (cmd == null) {
            throw ApiException.notFound("指令不存在");
        }
        try {
            BotCommand updated = new BotCommand(cmd.id(), cmd.trigger(), cmd.reply(),
                    cmd.description(), cmd.scope(), cmd.groupIds(), cmd.access(), cmd.enabled(),
                    cmd.sortOrder(), cmd.builtin(), cmd.kindName(), cmd.modeName(), cmd.params(),
                    trimToNull(body.help()), trimToNull(body.listReply()), cmd.action());
            store.saveExisting(id, updated);
            return ResponseEntity.noContent().build();
        } catch (Exception e) {
            throw ApiException.badRequest("保存失败：" + e.getMessage());
        }
    }

    // ==================== C6：使用统计 ====================

    @GetMapping("/stats")
    public CommandStats stats(@RequestParam(defaultValue = "30") int days) {
        // usage 是全部命中过的命令（不限 20 条）：统一命令表按行显示使用次数
        return new CommandStats(store.topUsed(days, 20), store.topUsed(days, 1000),
                store.unmatched(days, 20), store.count());
    }

    public record CommandStats(List<CommandRepository.UsageStat> topUsed,
                               List<CommandRepository.UsageStat> usage,
                               List<CommandRepository.UsageStat> unmatched, long total) {
    }

    // ==================== 内部 ====================

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
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

    @JsonIgnoreProperties(ignoreUnknown = false)

    public record UpsertCommandRequest(
            Long id,
            String trigger,
            String reply,
            String kind,
            List<ParamSpec> params,
            String action,
            String mode,
            String description,
            String scope,
            List<Long> groupIds,
            String minRole,
            Boolean enabled,
            Integer sortOrder,
            String help,
            String listReply) {
    }

    @JsonIgnoreProperties(ignoreUnknown = false)

    public record ToggleCommandRequest(Boolean enabled) {
    }

    @JsonIgnoreProperties(ignoreUnknown = false)

    public record AccessRequest(String access) {
    }

    @JsonIgnoreProperties(ignoreUnknown = false)

    public record PreviewRequest(String template, Boolean real, String args, List<ParamSpec> params) {
    }

    @JsonIgnoreProperties(ignoreUnknown = false)

    public record TextsRequest(String help, String listReply) {
    }

    public record CommandList(boolean available, List<CommandView> commands,
                              int rateLimitPerMinute, boolean allowUserIds) {
    }

    public record CatalogEntries(List<CommandCatalog.Entry> entries) {
    }

    public record CommandPolicyView(long id, String scope, List<Long> groups,
                                    boolean showGroup, boolean showUser, boolean allowPrivate) {
    }

    public record CommandPolicies(List<CommandPolicyView> policies) {
    }

    public record Variables(List<VariableRenderer.VarInfo> variables,
                            Map<String, String> preview, Map<String, String> current,
                            boolean allowUserIds) {
    }

    public record ActionView(String id, String label, String description,
                             String defaultPath, List<ParamSpec> params) {
    }

    public record Actions(List<ActionView> actions) {
    }

    public record SavedCommand(long id) {
    }

    public record PreviewResult(String rendered, int length) {
    }
}
