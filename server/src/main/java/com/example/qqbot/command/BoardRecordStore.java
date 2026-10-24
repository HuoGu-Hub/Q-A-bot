package com.example.qqbot.command;

import com.example.qqbot.persistence.BoardRecordRepository;
import com.example.qqbot.time.AppTime;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 板块记录的业务侧：增、查、软删、恢复。
 *
 * <h2>板块之间只有数据不同，行为一模一样</h2>
 * 所以四个动作由框架统一实现，板块只声明"存什么、投递方式、三把锁"。
 * 加一个板块 = 填一张表，不写代码。
 *
 * <h2>定时板块的约定</h2>
 * 参数表里唯一一个 REPEAT 类型的参数就是重复周期，名字随意；时间点优先认
 * 名为 {@code 时间} 的 MOMENT 参数，没有时用唯一的 MOMENT 参数。
 * 判定集中在 {@link Board#repeatParam()} 与 {@link Board#timeParam()}。
 */
@Component
public class BoardRecordStore {

    private static final Logger log = LoggerFactory.getLogger(BoardRecordStore.class);

    /** "什么时候发"的默认参数名；改过名的板块按类型/唯一 moment 认，见 Board#timeParam() */
    public static final String PARAM_TIME = "时间";
    /** "多久一次"的默认名，也是老配置（文本类型）的回落名，见 Board#repeatParam() */
    public static final String PARAM_REPEAT = "重复";

    private static final String STATUS_PENDING = "pending";

    private final BoardRecordRepository repo;
    private final ObjectMapper mapper;

    private volatile boolean available;

    public BoardRecordStore(BoardRecordRepository repo, ObjectMapper mapper) {
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
            log.info("[RECORD] 板块记录库就绪");
        } catch (Exception e) {
            log.warn("[RECORD] 初始化失败：{}", e.getMessage());
            available = false;
        }
    }

    public boolean isAvailable() {
        return available;
    }

    // ==================== 增 ====================

    /**
     * 收一条记录。
     *
     * <p>立即展示的板块直接落一条 direct 记录；到点发的板块顺便把"下次什么时候发"算好。
     *
     * @return 编号（全系统唯一，跨板块共用）
     * @throws CommandParseException 时间或重复写错
     */
    public long create(Board board, long groupId, long userId, String nickname,
                       String text, Map<String, String> params, List<Instant> moments,
                       Instant now) throws CommandParseException {
        if (!available) {
            throw new CommandParseException("记录", "暂时存不了，稍后再试");
        }
        if (!board.isScheduled()) {
            return repo.insert(new BoardRecordRepository.Row(0, board.path(), groupId, userId,
                    safe(nickname), safe(text), toJson(params), BoardRecordRepository.DIRECT,
                    null, null, "[]", null, STATUS_PENDING, 0, null,
                    iso(now), null, null));
        }
        // 到点发：时刻 -> 时刻组的两种存法，与 RepeatSchedule 的约定一致
        // 重复周期按类型认（名字随意）；老配置回落到「重复」这个名字
        ParamSpec repeat = Board.repeatParam(board.params());
        String repeatName = repeat == null ? PARAM_REPEAT : repeat.name();
        RepeatRule rule = RepeatRule.parse(params.get(repeatName));
        List<String> times = new ArrayList<>();
        for (Instant moment : moments) {
            times.add(rule.kind() == RepeatRule.Kind.ONCE
                    ? moment.toString()
                    : LocalDateTime.ofInstant(moment, AppTime.ZONE).toLocalTime()
                            .truncatedTo(ChronoUnit.MINUTES).toString());
        }
        // 「N 次」从第一次的时刻起算：anchor 取最早那个时刻的那一天，
        // 这样带日期写「10月25日21:30、3次」会发 25/26/27 三天，而不是从创建日算
        Instant anchor = rule.kind() == RepeatRule.Kind.TIMES
                ? moments.stream().min(Instant::compareTo).orElse(now)
                : now;
        RepeatSchedule schedule = new RepeatSchedule(rule, times, anchor);
        Instant fireAt = schedule.nextAfter(now);
        if (fireAt == null) {
            throw new CommandParseException("时间", "那个时间已经过去了，换个时间");
        }
        return repo.insert(new BoardRecordRepository.Row(0, board.path(), groupId, userId,
                safe(nickname), safe(text), toJson(params), BoardRecordRepository.SCHEDULED,
                iso(fireAt), rule.toStorage(), toJsonList(times), iso(anchor), STATUS_PENDING, 0,
                null, iso(now), null, null));
    }

    // ==================== 查 ====================

    public List<BoardRecord> query(Board board, long groupId, int limit) {
        return available ? toRecords(repo.listByBoard(board.path(), groupId, false, limit)) : List.of();
    }

    /**
     * 群友看的列表：隐藏已经执行完的有限周期（一次 / N 次）。
     *
     * <p>这些记录 status 已经是 sent，不会再发；留在列表里会让人以为还在排队。
     * 后台记录页仍走 {@link #query} / {@link #queryWithDeleted}，历史不丢。
     */
    public List<BoardRecord> queryVisible(Board board, long groupId, int limit) {
        return available ? toRecords(repo.listVisibleByBoard(board.path(), groupId, limit)) : List.of();
    }

    /** 指定群列表；板块数据范围 SOME 用 */
    public List<BoardRecord> query(Board board, List<Long> groupIds, int limit) {
        return available
                ? toRecords(repo.listByBoard(board.path(), groupIds, false, limit))
                : List.of();
    }

    /** 跨群列表同上：执行完的有限周期不显示 */
    public List<BoardRecord> queryVisible(Board board, List<Long> groupIds, int limit) {
        return available
                ? toRecords(repo.listVisibleByBoard(board.path(), groupIds, limit))
                : List.of();
    }

    /** 后台用：连已删的一起看，方便恢复 */
    public List<BoardRecord> queryWithDeleted(Board board, long groupId, int limit) {
        return available ? toRecords(repo.listByBoard(board.path(), groupId, true, limit)) : List.of();
    }

    public BoardRecord find(long id) {
        if (!available) {
            return null;
        }
        BoardRecordRepository.Row row = repo.findById(id);
        return row == null ? null : toRecord(row);
    }

    // ==================== 删与恢复 ====================

    /**
     * 软删。行还在，只是打了个标记 —— 删错了能恢复。
     *
     * @return false = 没有这条，或者本来就已经删过
     */
    public boolean softDelete(long id, Instant now) {
        return available && repo.softDelete(id, iso(now)) > 0;
    }

    public boolean restore(long id) {
        return available && repo.restore(id) > 0;
    }

    // ==================== 扫描器 ====================

    public List<BoardRecord> due(Instant now, int limit) {
        if (!available) {
            return List.of();
        }
        List<BoardRecord> out = new ArrayList<>();
        for (BoardRecordRepository.Row row : repo.due(iso(now), limit)) {
            if (repo.markSending(row.id(), iso(now)) == 1) {
                out.add(toRecord(row));
            }
        }
        return out;
    }

    public int reclaimStale(Instant now, int minutes) {
        return available ? repo.reclaimStaleSending(iso(now.minusSeconds(minutes * 60L))) : 0;
    }

    public void markSent(BoardRecord record, Instant now) {
        repo.markSent(record.id(), iso(now));
    }

    public void rearm(BoardRecord record, Instant next, Instant sentAt) {
        repo.rearm(record.id(), iso(next), iso(sentAt));
    }

    public void retryLater(BoardRecord record, String error, Instant nextTry) {
        repo.retryLater(record.id(), iso(nextTry), error);
    }

    public void markFailed(BoardRecord record, String error) {
        repo.close(record.id(), "failed", error);
    }

    public void markExpired(BoardRecord record) {
        repo.close(record.id(), "expired", "错过补发宽限期");
    }

    // ==================== 转换 ====================

    private List<BoardRecord> toRecords(List<BoardRecordRepository.Row> rows) {
        List<BoardRecord> out = new ArrayList<>(rows.size());
        for (BoardRecordRepository.Row row : rows) {
            out.add(toRecord(row));
        }
        return out;
    }

    private BoardRecord toRecord(BoardRecordRepository.Row r) {
        return new BoardRecord(r.id(), r.board(), r.groupId(), r.userId(), r.nickname(),
                r.text(), parseMap(r.paramsJson()), r.delivery(),
                parseInstant(r.fireAt()), r.repeatRule(), parseList(r.timesJson()),
                parseInstant(r.anchorAt()), r.status(), r.attempts(), r.lastError(),
                parseInstant(r.createdAt()), parseInstant(r.sentAt()),
                parseInstant(r.deletedAt()));
    }

    // ==================== 小工具 ====================

    static String iso(Instant at) {
        return at == null ? null : DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
                .withZone(ZoneOffset.UTC).format(at);
    }

    private static Instant parseInstant(String iso) {
        return iso == null || iso.isBlank() ? null : Instant.parse(iso);
    }

    private Map<String, String> parseMap(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return mapper.readValue(json, new TypeReference<LinkedHashMap<String, String>>() { });
        } catch (Exception e) {
            return Map.of();
        }
    }

    private List<String> parseList(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return mapper.readValue(json, new TypeReference<List<String>>() { });
        } catch (Exception e) {
            return List.of();
        }
    }

    private String toJson(Map<String, String> map) {
        try {
            return mapper.writeValueAsString(map == null ? Map.of() : map);
        } catch (Exception e) {
            return "{}";
        }
    }

    private String toJsonList(List<String> list) {
        try {
            return mapper.writeValueAsString(list == null ? List.of() : list);
        } catch (Exception e) {
            return "[]";
        }
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }
}
