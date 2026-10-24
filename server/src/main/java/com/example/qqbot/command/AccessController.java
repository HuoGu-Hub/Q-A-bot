package com.example.qqbot.command;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 权限与数据范围的管理接口。
 *
 * <p>认证由 {@code admin.AdminAuthFilter} 拦在 {@code /admin/api/**} 前面。
 *
 * <h2>为什么要单独一组接口</h2>
 * 名单与数据策略都很容易配错，而配错的后果是"某个人突然能用"或"某个群突然能看到别的群"。
 * 所以它们的读与写都做成独立的、整份覆盖的接口 —— 每次保存就是把这一份策略完整地换掉，
 * 不留半截状态。
 */
@RestController
@RequestMapping("/admin/api")
public class AccessController {

    private final AclStore aclStore;
    private final CommandPolicyStore policyStore;

    public AccessController(AclStore aclStore, CommandPolicyStore policyStore) {
        this.aclStore = aclStore;
        this.policyStore = policyStore;
    }

    // ==================== 名单 ====================

    /** 某条命令自己的名单 */
    @GetMapping("/commands/{id}/acl")
    public Map<String, Object> commandAcl(@PathVariable long id) {
        return view(aclStore.ofCommand(id));
    }

    /** 整份替换某条命令的名单 */
    @PostMapping("/commands/{id}/acl")
    public ResponseEntity<Map<String, Object>> saveCommandAcl(@PathVariable long id,
                                                              @RequestBody Map<String, Object> body) {
        if (id <= 0) {
            return bad("命令编号不对");
        }
        aclStore.replaceCommand(id, parse(body));
        return ResponseEntity.ok(view(aclStore.ofCommand(id)));
    }

    /** 某个板块自己的名单 */
    @GetMapping("/boards/{id}/acl")
    public Map<String, Object> boardAcl(@PathVariable long id) {
        return view(aclStore.ofBoard(id));
    }

    /** 整份替换某个板块的名单 */
    @PostMapping("/boards/{id}/acl")
    public ResponseEntity<Map<String, Object>> saveBoardAcl(@PathVariable long id,
                                                            @RequestBody Map<String, Object> body) {
        if (id <= 0) {
            return bad("板块编号不对");
        }
        aclStore.replaceBoard(id, parse(body));
        return ResponseEntity.ok(view(aclStore.ofBoard(id)));
    }

    /** 全局名单：对所有命令与板块生效 */
    @GetMapping("/acl/global")
    public Map<String, Object> globalAcl() {
        return view(aclStore.global());
    }

    @PostMapping("/acl/global")
    public ResponseEntity<Map<String, Object>> saveGlobalAcl(@RequestBody Map<String, Object> body) {
        aclStore.replaceGlobal(parse(body));
        return ResponseEntity.ok(view(aclStore.global()));
    }

    // ==================== 数据范围 ====================

    @GetMapping("/commands/{id}/policy")
    public Map<String, Object> policy(@PathVariable long id) {
        return viewOf(policyStore.of(id));
    }

    @PostMapping("/commands/{id}/policy")
    public ResponseEntity<Map<String, Object>> savePolicy(@PathVariable long id,
                                                          @RequestBody Map<String, Object> body) {
        if (id <= 0) {
            return bad("命令编号不对");
        }
        DataPolicy.DataScope scope;
        try {
            scope = DataPolicy.DataScope.valueOf(String.valueOf(body.getOrDefault("scope", "GROUP"))
                    .trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return bad("数据范围只能是 GROUP / SOME / ALL");
        }
        policyStore.save(id, new DataPolicy(scope, longs(body.get("groups")),
                bool(body.get("showGroup")), bool(body.get("showUser")),
                bool(body.get("allowPrivate"))));
        return ResponseEntity.ok(viewOf(policyStore.of(id)));
    }

    // ==================== 出参形状 ====================

    private static Map<String, Object> view(List<AclEntry> entries) {
        List<Map<String, Object>> white = new ArrayList<>();
        List<Map<String, Object>> black = new ArrayList<>();
        for (AclEntry entry : entries) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("type", entry.type());
            item.put("value", entry.value());
            (entry.isWhite() ? white : black).add(item);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("white", white);
        out.put("black", black);
        return out;
    }

    private static Map<String, Object> viewOf(DataPolicy policy) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("scope", policy.scope().name());
        out.put("groups", policy.groups());
        out.put("showGroup", policy.showGroup());
        out.put("showUser", policy.showUser());
        out.put("allowPrivate", policy.allowPrivate());
        return out;
    }

    // ==================== 入参解析 ====================

    /**
     * 把 {white:[{type,value}], black:[...]} 解析成条目。
     *
     * <p>只认 {@code uid} 与 {@code gid} 两种类型，其余一律丢掉 ——
     * 名单的"类型"只有这两种含义，多一个值就意味着有人写错了。
     */
    private static List<AclEntry> parse(Map<String, Object> body) {
        List<AclEntry> out = new ArrayList<>();
        collect(body.get("white"), AclEntry.WHITE, out);
        collect(body.get("black"), AclEntry.BLACK, out);
        return out;
    }

    private static void collect(Object raw, String scope, List<AclEntry> out) {
        if (!(raw instanceof List<?> list)) {
            return;
        }
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> map)) {
                continue;
            }
            String type = String.valueOf(map.get("type") == null ? AclEntry.UID : map.get("type"));
            if (!AclEntry.UID.equals(type) && !AclEntry.GID.equals(type)) {
                continue;
            }
            long value = toLong(map.get("value"));
            if (value > 0) {
                out.add(new AclEntry(scope, type, value));
            }
        }
    }

    private static List<Long> longs(Object raw) {
        List<Long> out = new ArrayList<>();
        if (raw instanceof List<?> list) {
            for (Object item : list) {
                long v = toLong(item);
                if (v > 0) {
                    out.add(v);
                }
            }
        }
        return out;
    }

    private static boolean bool(Object raw) {
        return raw instanceof Boolean b ? b : Boolean.parseBoolean(String.valueOf(raw));
    }

    private static long toLong(Object o) {
        if (o instanceof Number n) {
            return n.longValue();
        }
        try {
            return o == null ? 0L : Long.parseLong(String.valueOf(o).trim());
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    private static ResponseEntity<Map<String, Object>> bad(String msg) {
        return ResponseEntity.badRequest().body(Map.of("error", msg));
    }
}
