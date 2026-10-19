package com.example.qqbot.command;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 定时输出的管理接口（管理后台专用）。
 *
 * <p>认证由 {@code admin.AdminAuthFilter} 统一拦在 {@code /admin/api/**} 前面。
 *
 * <h2>为什么需要它</h2>
 * 群里的 {@code /list /output} 与 {@code /del /output} 只服务本群、且只能撤自己设的。
 * 管理员缺三件事：<b>看不到跨群全景</b>、<b>撤不掉别的群的任务</b>，
 * 以及最要紧的 —— <b>看不到失败原因</b>。任务发失败之后群里只表现为"该收到的没收到"，
 * 只有日志里一行 warn；{@code last_error} 那一列此前没有任何界面能看到。
 *
 * <h2>这里只读 + 撤销，不做编辑</h2>
 * 改内容、改时间会让"这条到底发没发过"变得说不清。要改就撤了重建，
 * 撤销是软删除，谁撤的、原定什么时候发，都还查得到。
 */
@RestController
@RequestMapping("/admin/api/schedules")
public class ScheduleController {

    private static final Logger log = LoggerFactory.getLogger(ScheduleController.class);

    /** 一次最多返回多少条：管理端是排查用的，不是导数据 */
    private static final int MAX_LIMIT = 200;

    private final ScheduleStore store;

    public ScheduleController(ScheduleStore store) {
        this.store = store;
    }

    /** 给管理端看的形状：不带 paramsJson（前端用不到），时刻用 ISO 串 */
    public record ScheduleView(long id, long groupId, long userId, String nickname, String content,
                               Instant fireAt, String repeatRule, String status, int attempts,
                               String lastError, Instant createdAt, Instant sentAt) {

        static ScheduleView of(ScheduleTask t) {
            return new ScheduleView(t.id(), t.groupId(), t.userId(), t.nickname(), t.content(),
                    t.fireAt(), t.repeatRule(), t.status(), t.attempts(), t.lastError(),
                    t.createdAt(), t.sentAt());
        }
    }

    /**
     * 任务列表。
     *
     * @param status  按状态筛；空或 all 表示不筛
     * @param groupId 按群筛；0 表示不筛
     * @param limit   条数上限，内部会夹到 1..200
     */
    @GetMapping("")
    public Map<String, Object> list(@RequestParam(required = false) String status,
                                    @RequestParam(defaultValue = "0") long groupId,
                                    @RequestParam(defaultValue = "50") int limit) {
        int capped = Math.min(Math.max(limit, 1), MAX_LIMIT);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("available", store.isAvailable());
        out.put("counts", store.statusCounts());
        List<ScheduleView> views = new ArrayList<>();
        for (ScheduleTask task : store.adminList(normalizeStatus(status), groupId, capped)) {
            views.add(ScheduleView.of(task));
        }
        out.put("tasks", views);
        return out;
    }

    /** 撤销任意一条待发任务 */
    @PostMapping("/cancel")
    public ResponseEntity<Map<String, Object>> cancel(@RequestBody Map<String, Object> body) {
        long id = toLong(body == null ? null : body.get("id"));
        if (id <= 0) {
            return bad("需要 id");
        }
        // 软删除：状态改成 canceled，谁撤销的与原定时刻都留在行里
        if (!store.cancel(id, "由管理员在后台撤销")) {
            return bad("这条任务不是待发状态，撤不了");
        }
        log.info("[SCHED] 管理员撤销 #{}", id);
        return ResponseEntity.ok(Map.of("ok", true, "id", id));
    }

    // ==================== 内部 ====================

    /** all / 空 → 不筛 */
    private static String normalizeStatus(String status) {
        if (status == null) {
            return null;
        }
        String s = status.trim();
        return s.isEmpty() || "all".equalsIgnoreCase(s) ? null : s;
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
