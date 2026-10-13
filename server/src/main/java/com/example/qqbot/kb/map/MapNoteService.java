package com.example.qqbot.kb.map;

import com.example.qqbot.kb.PlaceCategory;
import com.example.qqbot.persistence.KbMapNoteRepository;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * wiki 点位的**本地备注**读写服务。
 *
 * <h2>它解决什么</h2>
 * wiki 只告诉你"这里有个 {@code Ancient Spire}"；它不告诉你"这个尖塔离出生点最近"，
 * 也不会把 {@code Ancient Spire} 写成「远古尖塔」。这些**本地知识**写进备注表，
 * 于是：点位可以在不修改 wiki 镜像的前提下被检索、被当成方位参照、被翻译成中文。
 *
 * <h2>为什么备注不写回 wiki</h2>
 * 写回需要账号、要处理冲突、还会让"同步一次就能整体重刷"这个性质失效。
 * 备注是**叠加层**：镜像永远可以推倒重来，叠加层永远留着。
 */
@Service
public class MapNoteService {

    private static final Logger log = LoggerFactory.getLogger(MapNoteService.class);

    private static final int MAX_ALIAS = 120;
    private static final int MAX_NOTE = 500;

    /** 一条备注（落库/读出一致） */
    public record Note(String map, String page, String markerId, String alias, String note,
                       String category, boolean landmark, int priority, boolean hidden,
                       String updatedAt) {
    }

    private final KbMapNoteRepository repo;

    private volatile boolean available;

    public MapNoteService(KbMapNoteRepository repo) {
        this.repo = repo;
    }

    @PostConstruct
    public void init() {
        if (!repo.isAvailable()) {
            log.warn("[MAP-NOTE] 问答库不可用，点位备注一并关闭");
            available = false;
            return;
        }
        try {
            repo.initSchema();
            available = true;
            long n = repo.count();
            if (n > 0) {
                log.info("[MAP-NOTE] 点位备注就绪：{} 条", n);
            }
        } catch (Exception e) {
            log.warn("[MAP-NOTE] 初始化失败：{}", e.getMessage());
            available = false;
        }
    }

    public boolean isAvailable() {
        return available;
    }

    /**
     * 一张地图的全部备注，键是 {@code page#markerId}。
     *
     * <p>为什么给 Map 而不是 List：渲染点位时要给每个 marker 查一次备注，
     * 列表会退化成 O(n²) 的线性查找。键的形状与 {@code PlacePoint.ref()} 一致，
     * 两边能直接用同一个字符串对上。
     */
    public Map<String, Note> byMap(String map) {
        Map<String, Note> out = new LinkedHashMap<>();
        if (!available) {
            return out;
        }
        try {
            for (KbMapNoteRepository.NoteRow r : repo.byMap(map)) {
                out.put(ref(r.page(), r.markerId()), fromRow(r));
            }
        } catch (Exception e) {
            log.warn("[MAP-NOTE] 读取失败：{}", e.getMessage());
        }
        return out;
    }

    public List<Note> all() {
        if (!available) {
            return List.of();
        }
        try {
            return repo.byMap(null).stream().map(MapNoteService::fromRow).toList();
        } catch (Exception e) {
            log.warn("[MAP-NOTE] 读取失败：{}", e.getMessage());
            return List.of();
        }
    }

    /** 写入（upsert）。校验不过抛 {@link IllegalArgumentException} */
    public Note upsert(Note input) {
        if (!available) {
            throw new IllegalStateException("点位备注存储不可用（问答库没起来）");
        }
        if (input == null || input.map() == null || input.map().isBlank()) {
            throw new IllegalArgumentException("必须指定地图");
        }
        if (input.markerId() == null || input.markerId().isBlank()) {
            throw new IllegalArgumentException("必须指定点位 id");
        }
        String alias = clip(trim(input.alias()), MAX_ALIAS);
        String note = clip(trim(input.note()), MAX_NOTE);
        Note clean = new Note(trim(input.map()), trim(input.page()), trim(input.markerId()),
                alias, note, input.category() == null ? "" : PlaceCategory.normalize(input.category()),
                input.landmark(), Math.max(0, Math.min(100, input.priority())), input.hidden(),
                KbMapStore.now());
        repo.upsert(new KbMapNoteRepository.NoteRow(clean.map(), clean.page(), clean.markerId(),
                clean.alias(), clean.note(), clean.category(), clean.landmark(), clean.priority(),
                clean.hidden(), clean.updatedAt()));
        return clean;
    }

    public boolean delete(String map, String page, String markerId) {
        if (!available) {
            return false;
        }
        try {
            return repo.delete(map, page, markerId);
        } catch (Exception e) {
            log.warn("[MAP-NOTE] 删除失败：{}", e.getMessage());
            return false;
        }
    }

    public long count() {
        return available ? repo.count() : 0;
    }

    /** 与 {@code PlacePoint.ref()} 同一形状 —— 两边必须拼得一模一样 */
    public static String ref(String page, String markerId) {
        return (page == null ? "" : page) + "#" + (markerId == null ? "" : markerId);
    }

    private static Note fromRow(KbMapNoteRepository.NoteRow r) {
        return new Note(r.map(), r.page(), r.markerId(), r.alias(), r.note(), r.category(),
                r.landmark(), r.priority(), r.hidden(), r.updatedAt());
    }

    private static String trim(String s) {
        return s == null ? "" : s.strip();
    }

    private static String clip(String s, int max) {
        return s.length() > max ? s.substring(0, max) : s;
    }
}
