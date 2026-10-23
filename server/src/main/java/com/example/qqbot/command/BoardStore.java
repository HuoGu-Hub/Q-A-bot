package com.example.qqbot.command;

import com.example.qqbot.persistence.BoardRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Instant;
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

    /**
     * 定时板块的两个基础参数。
     *
     * <p>名字是约定：{@link BoardRecordStore#PARAM_REPEAT} 与 {@link BoardRecordStore#PARAM_TIME}
     * 就是靠这两个名字认出来的，改名字时要一起改。
     */
    private static final ParamSpec REPEAT = ParamSpec.of("重复", "text", true, "每天");
    private static final ParamSpec TIME = ParamSpec.multi(
            "时间", ParamType.MOMENT, true, null, 0, "几点发，可以写多个，用顿号隔开", "21:30");

    private final BoardRepository repo;
    private final ObjectMapper mapper;

    private volatile boolean available;

    /** 新库是否种三个示例板块；后台/生产默认关，测试直接构造时保留种子 */
    private final boolean seedDefaults;

    @Autowired
    public BoardStore(BoardRepository repo, ObjectMapper mapper,
                      @Value("${app.boards.seed-defaults:false}") boolean seedDefaults) {
        this.repo = repo;
        this.mapper = mapper;
        this.seedDefaults = seedDefaults;
    }

    /** 测试用：默认允许种子，保证原有的板块用例不必自己造数据 */
    BoardStore(BoardRepository repo, ObjectMapper mapper) {
        this(repo, mapper, true);
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
            // 种子只播一次：清空板块后重启不能再把三个内置板块塞回来
            if (seedDefaults && !repo.hasMeta("board-seed-retired")) {
                seedBuiltins();
                repo.setMeta("board-seed-retired", Instant.now().toString());
            }
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
                params(REPEAT, TIME),
                Board.SCHEDULED, "admin", "GROUP", "[]",
                false, false, false, true, 10, true));

        repo.seed(new BoardRepository.Row(0, "任务", "任务",
                params(ParamSpec.of("事项", "text", true, "打卡"),
                        REPEAT, TIME),
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

    /**
     * 保存板块。
     *
     * @throws IllegalStateException 板块库不可用
     * @throws IllegalArgumentException 参数表序列化失败
     */
    public void save(Board board) {
        if (!available) {
            throw new IllegalStateException("板块库不可用");
        }
        String groupsJson;
        String paramsJson;
        try {
            groupsJson = mapper.writeValueAsString(board.policy().groups());
            paramsJson = mapper.writeValueAsString(board.params());
        } catch (Exception e) {
            throw new IllegalArgumentException("板块参数序列化失败：" + e.getMessage(), e);
        }
        repo.save(new BoardRepository.Row(0, board.path(), board.title(), paramsJson,
                board.delivery(), board.access(), board.policy().scope().name(), groupsJson,
                board.policy().showGroup(), board.policy().showUser(),
                board.policy().allowPrivate(), board.enabled(), board.sortOrder(),
                board.builtin(), board.listHeader(), board.listItem(), board.listEmpty(),
                board.listLimit(), board.listReply(), board.helpReply(), board.description()));
    }

    /**
     * 删除板块。
     *
     * <p>仓储层只删 {@code builtin=0} 的行；内置板块返回 false，由调用方给提示。
     */
    public boolean delete(String path) {
        return available && repo.delete(path) > 0;
    }

    /** 改板块名；历史记录由仓储层在同一事务里迁移 */
    public boolean rename(String oldPath, String newPath) {
        return available && repo.rename(oldPath, newPath);
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
                r.enabled(), r.sortOrder(), r.builtin(),
                r.listHeader(), r.listItem(), r.listEmpty(), r.listLimit(),
                r.listReply(), r.helpReply(), r.description());
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
