package com.example.qqbot.command;

import io.swagger.v3.oas.annotations.security.SecurityRequirement;

import io.swagger.v3.oas.annotations.tags.Tag;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import com.example.qqbot.web.ApiException;

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
@Tag(name = "Admin")
@SecurityRequirement(name = "adminSession")
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
    public AclView commandAcl(@PathVariable long id) {
        return view(aclStore.ofCommand(id));
    }

    /** 整份替换某条命令的名单 */
    @PostMapping("/commands/{id}/acl")
    public ResponseEntity<AclView> saveCommandAcl(@PathVariable long id,
                                                              @RequestBody AclRequest body) {
        if (id <= 0) {
            throw ApiException.badRequest("命令编号不对");
        }
        aclStore.replaceCommand(id, parse(body));
        return ResponseEntity.ok(view(aclStore.ofCommand(id)));
    }

    /** 某个板块自己的名单 */
    @GetMapping("/boards/{id}/acl")
    public AclView boardAcl(@PathVariable long id) {
        return view(aclStore.ofBoard(id));
    }

    /** 整份替换某个板块的名单 */
    @PostMapping("/boards/{id}/acl")
    public ResponseEntity<AclView> saveBoardAcl(@PathVariable long id,
                                                            @RequestBody AclRequest body) {
        if (id <= 0) {
            throw ApiException.badRequest("板块编号不对");
        }
        aclStore.replaceBoard(id, parse(body));
        return ResponseEntity.ok(view(aclStore.ofBoard(id)));
    }

    /** 全局名单：对所有命令与板块生效 */
    @GetMapping("/acl/global")
    public AclView globalAcl() {
        return view(aclStore.global());
    }

    @PostMapping("/acl/global")
    public ResponseEntity<AclView> saveGlobalAcl(@RequestBody AclRequest body) {
        aclStore.replaceGlobal(parse(body));
        return ResponseEntity.ok(view(aclStore.global()));
    }

    // ==================== 数据范围 ====================

    @GetMapping("/commands/{id}/policy")
    public PolicyView policy(@PathVariable long id) {
        return viewOf(policyStore.of(id));
    }

    @PostMapping("/commands/{id}/policy")
    public ResponseEntity<PolicyView> savePolicy(@PathVariable long id,
                                                          @RequestBody PolicyRequest body) {
        if (id <= 0) {
            throw ApiException.badRequest("命令编号不对");
        }
        DataPolicy.DataScope scope;
        try {
            scope = DataPolicy.DataScope.valueOf(
                    body.scope() == null ? "GROUP" : body.scope().trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("数据范围只能是 GROUP / SOME / ALL");
        }
        policyStore.save(id, new DataPolicy(scope,
                body.groups() == null ? List.of() : body.groups(),
                Boolean.TRUE.equals(body.showGroup()),
                Boolean.TRUE.equals(body.showUser()),
                Boolean.TRUE.equals(body.allowPrivate())));
        return ResponseEntity.ok(viewOf(policyStore.of(id)));
    }

    // ==================== 出参形状 ====================

    private static AclView view(List<AclEntry> entries) {
        List<AclItemView> white = new ArrayList<>();
        List<AclItemView> black = new ArrayList<>();
        for (AclEntry entry : entries) {
            AclItemView item = new AclItemView(entry.type(), entry.value());
            (entry.isWhite() ? white : black).add(item);
        }
        return new AclView(white, black);
    }

    private static PolicyView viewOf(DataPolicy policy) {
        return new PolicyView(policy.scope().name(), policy.groups(),
                policy.showGroup(), policy.showUser(), policy.allowPrivate());
    }

    public record AclItemView(String type, long value) {
    }

    public record AclView(List<AclItemView> white, List<AclItemView> black) {
    }

    public record PolicyView(String scope, List<Long> groups, boolean showGroup,
                             boolean showUser, boolean allowPrivate) {
    }

    // ==================== 入参解析 ====================

    private static List<AclEntry> parse(AclRequest body) {
        List<AclEntry> out = new ArrayList<>();
        collect(body.white(), AclEntry.WHITE, out);
        collect(body.black(), AclEntry.BLACK, out);
        return out;
    }

    private static void collect(List<AclItemRequest> items, String scope, List<AclEntry> out) {
        if (items == null) {
            return;
        }
        for (AclItemRequest item : items) {
            if (item == null) {
                continue;
            }
            String type = item.type() == null ? AclEntry.UID : item.type();
            if (!AclEntry.UID.equals(type) && !AclEntry.GID.equals(type)) {
                continue;
            }
            if (item.value() != null && item.value() > 0) {
                out.add(new AclEntry(scope, type, item.value()));
            }
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = false)

    public record AclItemRequest(String type, Long value) {
    }

    @JsonIgnoreProperties(ignoreUnknown = false)

    public record AclRequest(List<AclItemRequest> white, List<AclItemRequest> black) {
    }

    @JsonIgnoreProperties(ignoreUnknown = false)

    public record PolicyRequest(String scope, List<Long> groups, Boolean showGroup,
                                Boolean showUser, Boolean allowPrivate) {
    }
}
