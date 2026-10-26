package com.example.qqbot.voice;

import com.example.qqbot.persistence.VoiceTriggerRepository;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.text.Normalizer;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 关键词触发 —— 只回答一个问题：**这条消息要不要用语音回**。
 *
 * <h2>为什么不绑定内容</h2>
 * 关键词说"唱首歌"，不代表每次都放同一首。命中之后照常问模型，
 * 由它决定从素材库里挑哪段、还是现场合成。这里只负责举手。
 *
 * <h2>冷却为什么按「群 + 规则」</h2>
 * 群里一个人连着刷「唱首歌」，不该每次都触发。按群算是为了防止刷屏，
 * 按规则算是为了「唱首歌」的冷却不影响「晚安」。
 */
@Component
public class VoiceTriggerService {

    private static final Logger log = LoggerFactory.getLogger(VoiceTriggerService.class);

    /** 默认冷却（秒） */
    static final int DEFAULT_COOLDOWN = 120;

    /** 首次启动时种的示例规则：只触发语音，不指定内容 */
    private static final List<String> SEED_KEYWORDS = List.of(
            "唱首歌", "唱歌", "来一首", "喵", "叫一声", "晚安");

    private final VoiceTriggerRepository repo;

    /** 规则 id → 上次触发时间（毫秒）。进程重启后清空，宁可多触发一次 */
    private final Map<String, Long> lastFired = new ConcurrentHashMap<>();

    private volatile boolean available;
    private volatile List<Rule> cache = List.of();

    public VoiceTriggerService(VoiceTriggerRepository repo) {
        this.repo = repo;
    }

    /** 一条规则。{@code groupScope} 为空表示所有群 */
    public record Rule(long id, String keyword, int cooldownSeconds, String groupScope,
                       boolean enabled, String note) {
    }

    @PostConstruct
    public void init() {
        if (!repo.isAvailable()) {
            log.warn("[VOICE] 数据库不可用，关键词触发关闭");
            return;
        }
        try {
            repo.initSchema();
            if (repo.count() == 0) {
                for (String keyword : SEED_KEYWORDS) {
                    repo.insert(keyword, DEFAULT_COOLDOWN, "", "默认：只触发语音，内容由模型挑",
                            Instant.now().toString());
                }
                log.info("[VOICE] 已种入 {} 条默认触发词", SEED_KEYWORDS.size());
            }
            available = true;
            reload();
            log.info("[VOICE] 触发词就绪：{} 条", cache.size());
        } catch (Exception e) {
            log.warn("[VOICE] 触发词初始化失败：{}", e.getMessage());
            available = false;
        }
    }

    public boolean isAvailable() {
        return available && repo.isAvailable();
    }

    public List<Rule> rules() {
        return cache;
    }

    /** 改完规则后调用，让内存里的快照跟上 */
    public synchronized void reload() {
        try {
            cache = repo.all().stream()
                    .map(r -> new Rule(r.id(), r.keyword(), r.cooldownSeconds(), r.groupScope(),
                            r.enabled(), r.note()))
                    .toList();
        } catch (Exception e) {
            log.warn("[VOICE] 读取触发词失败：{}", e.getMessage());
        }
    }

    /**
     * 这条消息要不要用语音回。
     *
     * <p>命中多条时取**关键词最长**的那条 —— 「唱首歌」比「歌」更明确，
     * 冷却也按胜出的那条算。
     */
    public boolean requiresVoice(Long groupId, String text) {
        if (!isAvailable() || text == null || text.isBlank()) {
            return false;
        }
        String normalized = normalize(text);
        Rule best = null;
        for (Rule rule : cache) {
            if (!rule.enabled() || rule.keyword().isBlank()) {
                continue;
            }
            if (!inScope(rule, groupId)) {
                continue;
            }
            if (normalized.contains(normalize(rule.keyword()))
                    && (best == null || rule.keyword().length() > best.keyword().length())) {
                best = rule;
            }
        }
        if (best == null) {
            return false;
        }
        String key = (groupId == null ? 0L : groupId) + ":" + best.id();
        long now = System.currentTimeMillis();
        Long last = lastFired.get(key);
        if (last != null && now - last < Math.max(0, best.cooldownSeconds()) * 1000L) {
            log.info("[VOICE] 触发词「{}」在冷却中（群 {}）", best.keyword(), groupId);
            return false;
        }
        lastFired.put(key, now);
        log.info("[VOICE] 触发词「{}」命中（群 {}），本条回复必须带语音", best.keyword(), groupId);
        return true;
    }

    // ==================== 管理端写入 ====================

    /** 新增一条。返回 null 表示失败（库不可用或关键词重复） */
    public Rule add(String keyword, int cooldownSeconds, String groupScope, String note) {
        String kw = keyword == null ? "" : keyword.trim();
        if (!isAvailable() || kw.isEmpty()) {
            return null;
        }
        try {
            long id = repo.insert(kw, Math.max(0, cooldownSeconds), nullToEmpty(groupScope),
                    nullToEmpty(note), Instant.now().toString());
            reload();
            return cache.stream().filter(r -> r.id() == id).findFirst().orElse(null);
        } catch (Exception e) {
            log.warn("[VOICE] 新增触发词失败：{}", e.getMessage());
            return null;
        }
    }

    /** 改一条。返回更新后的规则，没有这条返回 null */
    public Rule update(long id, int cooldownSeconds, String groupScope, String note, boolean enabled) {
        Rule old = cache.stream().filter(r -> r.id() == id).findFirst().orElse(null);
        if (old == null) {
            return null;
        }
        try {
            repo.update(id, Math.max(0, cooldownSeconds), nullToEmpty(groupScope), nullToEmpty(note), enabled);
            reload();
            return cache.stream().filter(r -> r.id() == id).findFirst().orElse(null);
        } catch (Exception e) {
            log.warn("[VOICE] 更新触发词失败：{}", e.getMessage());
            return null;
        }
    }

    public boolean delete(long id) {
        if (!isAvailable()) {
            return false;
        }
        try {
            repo.delete(id);
            lastFired.keySet().removeIf(k -> k.endsWith(":" + id));
            reload();
            return true;
        } catch (Exception e) {
            log.warn("[VOICE] 删除触发词失败：{}", e.getMessage());
            return false;
        }
    }

    private static String nullToEmpty(String v) {
        return v == null ? "" : v.trim();
    }

    private static boolean inScope(Rule rule, Long groupId) {
        String scope = rule.groupScope();
        if (scope == null || scope.isBlank()) {
            return true;
        }
        if (groupId == null) {
            return false;
        }
        for (String part : scope.split(",")) {
            if (part.trim().equals(String.valueOf(groupId))) {
                return true;
            }
        }
        return false;
    }

    /** 全角转半角、去空白、统一小写，避免「唱 首 歌」绕过 */
    static String normalize(String text) {
        String half = Normalizer.normalize(text, Normalizer.Form.NFKC);
        return half.replaceAll("\\s+", "").toLowerCase(java.util.Locale.ROOT);
    }
}
