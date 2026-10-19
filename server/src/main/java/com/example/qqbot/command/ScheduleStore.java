package com.example.qqbot.command;

import com.example.qqbot.persistence.ScheduleRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 定时输出的存储。
 *
 * <p>与指令表共用同一个 SQLite 文件 —— 都是本地小数据。表由本类自建。
 *
 * <h2>时间字符串的格式必须<b>定长</b></h2>
 * 扫描用 {@code fire_at <= ?} 比较，而 {@code Instant.toString()} 的精度不固定：
 * {@code 13:30:00Z} 与 {@code 13:30:00.123Z} 按字典序比会与时间序相反
 * （{@code '.'} < {@code 'Z'}）。所以这里统一裁到毫秒再格式化，
 * <b>所有写进 fire_at 的值都必须过 {@link #iso}</b>。
 *
 * <h2>状态机</h2>
 * <pre>
 *   pending ──抢行──▶ sending ──成功──▶ sent          一次性到此为止
 *      ▲                  │      └──周期──▶ pending   换下一个时刻
 *      │                  └──失败──▶ pending 或 failed
 *      └────────── 重试 ─────────────────────┘
 *   pending ──错过太久──▶ expired
 *   pending ──群友撤销──▶ canceled
 * </pre>
 */
@Component
public class ScheduleStore {

    private static final Logger log = LoggerFactory.getLogger(ScheduleStore.class);

    /** 定长 ISO：毫秒固定三位，UTC。字典序即时间序，见类注释 */
    private static final DateTimeFormatter ISO = DateTimeFormatter
            .ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

    public static final String STATUS_PENDING = "pending";
    /** 抢行中的瞬时状态，正常一轮之内就会离开它 */
    public static final String STATUS_SENDING = "sending";
    public static final String STATUS_SENT = "sent";
    public static final String STATUS_FAILED = "failed";
    public static final String STATUS_EXPIRED = "expired";
    public static final String STATUS_CANCELED = "canceled";

    public static final String REPEAT_ONCE = ScheduleTask.REPEAT_ONCE;
    public static final String REPEAT_DAILY = ScheduleTask.REPEAT_DAILY;

    private final ScheduleRepository repo;
    private final ObjectMapper mapper;
    private volatile boolean available;

    public ScheduleStore(ScheduleRepository repo, ObjectMapper mapper) {
        this.repo = repo;
        this.mapper = mapper;
    }

    @PostConstruct
    void init() {
        try {
            if (!repo.isAvailable()) {
                log.warn("[SCHED] 问答库不可用，定时输出一并关闭");
                available = false;
                return;
            }
            repo.initSchema();
            available = true;
            log.info("[SCHED] 定时输出表就绪");
        } catch (Exception e) {
            log.warn("[SCHED] 初始化定时输出失败，该功能不可用（不影响其他功能）：{}", e.getMessage());
            available = false;
        }
    }

    public boolean isAvailable() {
        return available;
    }

    /** Instant → 定长 ISO 串。任何写进 {@code fire_at} 的值都必须过这里 */
    public static String iso(Instant at) {
        return ISO.format(at.truncatedTo(java.time.temporal.ChronoUnit.MILLIS));
    }

    /** 时刻组那一列的 JSON 原文 → 列表；读坏了就当空表，不让一条脏记录卡住整轮扫描 */
    private List<String> parseTimes(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return mapper.readValue(json, new TypeReference<List<String>>() { });
        } catch (Exception e) {
            return List.of();
        }
    }

    private static Instant parse(String iso) {
        return iso == null || iso.isBlank() ? null : Instant.parse(iso);
    }

    // ==================== 写 ====================

    /**
     * 记一条定时输出。
     *
     * @param content 模板原文，{@code {time}} 之类的变量留到发送时再展开
     * @param args    当次收到的参数原样值，供发送时的 {@code {arg.名称}} 取值
     */
    public long create(long groupId, long userId, String nickname, String content,
                       Map<String, String> args, Instant fireAt, String repeatRule) throws IOException {
        return create(groupId, userId, nickname, content, args, fireAt, repeatRule, List.of(), null);
    }

    /**
     * 记一条定时输出。
     *
     * @param times  时刻组；一次性放绝对时刻，周期放 HH:mm
     * @param anchor 创建时刻，{@code 每N天} 从它起算
     */
    public long create(long groupId, long userId, String nickname, String content,
                       Map<String, String> args, Instant fireAt, String repeatRule,
                       List<String> times, Instant anchor) throws IOException {
        if (!available) {
            throw new IOException("定时输出不可用");
        }
        String paramsJson;
        try {
            paramsJson = mapper.writeValueAsString(args == null ? Map.of() : args);
        } catch (Exception e) {
            throw new IOException("参数序列化失败：" + e.getMessage(), e);
        }
        String timesJson;
        try {
            timesJson = mapper.writeValueAsString(times == null ? List.of() : times);
        } catch (Exception e) {
            throw new IOException("时刻序列化失败：" + e.getMessage(), e);
        }
        String now = iso(Instant.now());
        return repo.insert(new ScheduleRepository.Row(0, groupId, userId,
                nickname == null ? "" : nickname, content, paramsJson,
                iso(fireAt), repeatRule, STATUS_PENDING, 0, null, now, null,
                timesJson, anchor == null ? null : iso(anchor)));
    }

    /** 撤销：不是删除，保留是谁、何时撤的，便于事后看"发过的都去哪了" */
    public boolean cancel(long id, String reason) {
        if (!available) {
            return false;
        }
        return repo.cancel(id, reason) > 0;
    }

    /** 一个人在一个会话里最多能挂几条 */
    public long countPending(long userId, long groupId) {
        return available ? repo.countPendingByUser(userId, groupId) : 0;
    }

    // ==================== 读 ====================

    public Optional<ScheduleTask> find(long id) {
        if (!available) {
            return Optional.empty();
        }
        ScheduleRepository.Row row = repo.findById(id);
        return row == null ? Optional.empty() : Optional.of(toTask(row));
    }

    public List<ScheduleTask> pendingForGroup(long groupId, int limit) {
        return available ? toTasks(repo.pendingByGroup(groupId, limit)) : List.of();
    }

    public List<ScheduleTask> pendingForUser(long userId, int limit) {
        return available ? toTasks(repo.pendingByUser(userId, limit)) : List.of();
    }

    // ==================== 管理端 ====================

    /** 管理端列表：按状态与群号筛 */
    public List<ScheduleTask> adminList(String status, long groupId, int limit) {
        return available ? toTasks(repo.adminQuery(status, groupId, limit)) : List.of();
    }

    /**
     * 各状态条数，给概览用。
     *
     * <p><b>没有行的状态也补 0</b>：SQL 的 GROUP BY 只会返回出现过的状态，
     * 于是"撤销掉最后一条待发之后，概览里待发那一格整块消失" ——
     * 前端得靠 {@code ?? 0} 兜，换个消费方又会踩一次。这里补全，接口形状就稳定了。
     */
    public Map<String, Long> statusCounts() {
        if (!available) {
            return Map.of();
        }
        Map<String, Long> raw = repo.countByStatus();
        Map<String, Long> out = new LinkedHashMap<>();
        for (String status : List.of(STATUS_PENDING, STATUS_SENDING, STATUS_SENT,
                STATUS_FAILED, STATUS_EXPIRED, STATUS_CANCELED)) {
            out.put(status, raw.getOrDefault(status, 0L));
        }
        return out;
    }

    // ==================== 扫描器专用 ====================

    /**
     * 抢到期的任务。
     *
     * <p>逐行条件更新：更新到的那一行才算抢到。两个 tick 撞上、或者
     * 以后真起了第二个实例，都不会把同一条发两遍。
     */
    public List<ScheduleTask> claimDue(Instant now, int limit) {
        if (!available) {
            return List.of();
        }
        List<ScheduleTask> claimed = new ArrayList<>();
        for (ScheduleRepository.Row row : repo.due(iso(now), limit)) {
            if (repo.markSending(row.id(), iso(now)) == 1) {
                claimed.add(toTask(row));
            }
        }
        return claimed;
    }

    /**
     * 把卡在「发送中」超过 {@code minutes} 分钟的行放回待发。
     *
     * @return 放回了几行
     */
    public int reclaimStale(Instant now, int minutes) {
        if (!available) {
            return 0;
        }
        return repo.reclaimStaleSending(iso(now.minusSeconds(minutes * 60L)));
    }

    public void markSent(ScheduleTask task, Instant now) {
        repo.markSent(task.id(), iso(now));
    }

    /** 周期任务：记一次已发，回到待发 */
    public void rearm(ScheduleTask task, Instant nextFire, Instant sentAt) {
        repo.rearm(task.id(), iso(nextFire), iso(sentAt));
    }

    public void retryLater(ScheduleTask task, String error, Instant nextTry) {
        repo.retryLater(task.id(), iso(nextTry), error);
    }

    public void markFailed(ScheduleTask task, String error) {
        repo.close(task.id(), STATUS_FAILED, error);
    }

    public void markExpired(ScheduleTask task) {
        repo.close(task.id(), STATUS_EXPIRED, "错过补发宽限期");
    }

    // ==================== 转换 ====================

    private List<ScheduleTask> toTasks(List<ScheduleRepository.Row> rows) {
        List<ScheduleTask> out = new ArrayList<>(rows.size());
        for (ScheduleRepository.Row row : rows) {
            out.add(toTask(row));
        }
        return out;
    }

    private ScheduleTask toTask(ScheduleRepository.Row r) {
        return new ScheduleTask(r.id(), r.groupId(), r.userId(), r.nickname(), r.content(),
                r.paramsJson(), parse(r.fireAt()), r.repeatRule(), r.status(), r.attempts(),
                r.lastError(), parse(r.createdAt()), parse(r.sentAt()),
                parseTimes(r.timesJson()), parse(r.anchorAt()));
    }

    /**
     * 任务行里存着的参数原样值。
     *
     * <p>坏数据当空处理：一条任务的参数坏了，最坏是 {@code {arg.名称}} 取不到值
     * 而原样保留，不该让这条任务发不出去。
     */
    public Map<String, String> argsOf(ScheduleTask task) {
        if (task == null || task.paramsJson() == null || task.paramsJson().isBlank()) {
            return Map.of();
        }
        try {
            return mapper.readValue(task.paramsJson(), new TypeReference<Map<String, String>>() { });
        } catch (Exception e) {
            log.warn("[SCHED] 任务 #{} 的参数解析失败，按无参数处理：{}", task.id(), e.getMessage());
            return Map.of();
        }
    }
}
