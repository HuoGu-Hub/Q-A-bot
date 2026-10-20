package com.example.qqbot.command;

import com.example.qqbot.persistence.BoardRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 板块的业务侧。
 *
 * <p>板块是<b>配置</b>：管理员能加一个板块、改它的参数表，不用改程序。
 * 内置的三个只是种子，改过之后不会被重启覆盖。
 */
@Component
public class BoardStore {

    private static final Logger log = LoggerFactory.getLogger(BoardStore.class);

    private final BoardRepository repo;
    private final ObjectMapper mapper;

    private volatile boolean available;

    public BoardStore(BoardRepository repo, ObjectMapper mapper) {
        this.repo = repo;
        this.mapper = mapper;
    }

    @PostConstruct
    void init() {
        try {
            if (!repo.isAvailable()) {
                log.warn("[BOARD] 数据库不可用，板块功能一并关闭");
                available = false;
                return;
            }
            repo.initSchema();
            seedBuiltins();
            available = true;
            log.info("[BOARD] 板块就绪：{} 个", listAll().size());
        } catch (Exception e) {
            log.warn("[BOARD] 初始化失败：{}", e.getMessage());
            available = false;
        }
    }

    /**
     * 三个内置板块。
     *
     * <p>公告与任务都是"到点发"，区别只在于谁能发：公告是本群的门面，管理员才动；
     * 任务是谁都能给自己挂的提醒。开黑是"立即展示"，而且要跨群。
     */
    private void seedBuiltins() {
        repo.seed(new BoardRepository.Row(0, "公告", "公告",
                params(ScheduleCreateAction.REPEAT, ScheduleCreateAction.TIME),
                Board.SCHEDULED, "admin", "GROUP", "[]",
                false, false, false, true, 10, true));

        repo.seed(new BoardRepository.Row(0, "任务", "任务",
                params(ParamSpec.of("事项", "text", true, "打卡"),
                        ScheduleCreateAction.REPEAT, ScheduleCreateAction.TIME),
                Board.SCHEDULED, "all", "GROUP", "[]",
                false, false, false, true, 20, true));

        repo.seed(new BoardRepository.Row(0, "开黑", "开黑",
                params(ParamSpec.of("空闲", "text", true, "今晚8点"),
                        ParamSpec.of("风格", "text", false, "上分"),
                        ParamSpec.of("联系", "text", false, "QQ 123456")),
                Board.DIRECT, "all", "ALL", "[]",
                true, false, false, true, 30, true));
    }

    private String params(ParamSpec... specs) {
        try {
            return mapper.writeValueAsString(List.of(specs));
        } catch (Exception e) {
            return "[]";
        }
    }

    public boolean isAvailable() {
        return available;
    }

    public List<Board> listAll() {
        return available ? toBoards(repo.listAll()) : List.of();
    }

    public List<Board> listEnabled() {
        return available ? toBoards(repo.listEnabled()) : List.of();
    }

    /** 按板块名找；找不到返回 null（调用方要能区分"没这个板块"和"板块停用了"） */
    public Board find(String path) {
        if (!available || path == null || path.isBlank()) {
            return null;
        }
        BoardRepository.Row row = repo.findByPath(path.trim());
        return row == null ? null : toBoard(row);
    }

    public void save(Board board) {
        if (!available) {
            return;
        }
        String groupsJson;
        String paramsJson;
        try {
            groupsJson = mapper.writeValueAsString(board.policy().groups());
            paramsJson = mapper.writeValueAsString(board.params());
        } catch (Exception e) {
            return;
        }
        repo.save(new BoardRepository.Row(0, board.path(), board.title(), paramsJson,
                board.delivery(), board.access(), board.policy().scope().name(), groupsJson,
                board.policy().showGroup(), board.policy().showUser(),
                board.policy().allowPrivate(), board.enabled(), board.sortOrder(),
                board.builtin()));
    }

    public void delete(String path) {
        if (available) {
            repo.delete(path);
        }
    }

    // ==================== 转换 ====================

    private List<Board> toBoards(List<BoardRepository.Row> rows) {
        List<Board> out = new ArrayList<>(rows.size());
        for (BoardRepository.Row row : rows) {
            out.add(toBoard(row));
        }
        return out;
    }

    private Board toBoard(BoardRepository.Row r) {
        return new Board(r.id(), r.path(), r.title(), parseParams(r.paramsJson()), r.delivery(),
                r.access(),
                new DataPolicy(parseScope(r.scope()), parseGroups(r.groupsJson()),
                        r.showGroup(), r.showUser(), r.allowPrivate()),
                r.enabled(), r.sortOrder(), r.builtin());
    }

    private List<ParamSpec> parseParams(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return mapper.readValue(json, new TypeReference<List<ParamSpec>>() { });
        } catch (Exception e) {
            log.warn("[BOARD] 参数表读不出来，按空表处理：{}", e.getMessage());
            return List.of();
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

    private static DataPolicy.DataScope parseScope(String raw) {
        try {
            return DataPolicy.DataScope.valueOf(raw == null ? "GROUP" : raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return DataPolicy.DataScope.GROUP;
        }
    }

    /** 板块 → 四个动作的说明，后台与 {@code /list} 都用它 */
    public Map<String, Object> actionsOf(Board board) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (String action : Board.ACTIONS) {
            out.put(action, "/" + board.path() + " /" + action);
        }
        return out;
    }
}
