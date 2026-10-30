package com.example.qqbot.settings;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 配置中心的服务：读取当前值 → 校验 → 热应用 → 持久化。
 *
 * <p>热生效的原理：这几个 @ConfigurationProperties 对象是被各个 Bean
 * 长期持有引用的（如 {@code private final RateLimit config}），调用时每次都实时读字段。
 * 所以改掉对象里的字段值，下一次调用就生效 ——
 * 不需要 Actuator、不需要 @RefreshScope、不需要重建 Bean。
 *
 * <p>改动同时写入 config/overrides.yml 持久化，重启后仍是新值。
 *
 * <p>只有 SettingsWhitelist 白名单里的 key 能改，其余直接拒绝。
 */
@Component
public class SettingsService {

    private static final Logger log = LoggerFactory.getLogger(SettingsService.class);

    /** 覆盖层文件名（相对运行目录） */

    private final SettingsWhitelist whitelist;
    private final OverridesFile overrides;

    /**
     * 顶层前缀 → 承载它的配置对象（由 {@link SettingsRoots} 递进来）。
     *
     * <p><b>本类不认识任何 {@code config} 类型</b> —— 它全程用反射
     * （{@code getDeclaredField} + {@code Field.set}）按**字段名**读写，
     * 从不静态引用某个配置字段。那张表由**装配层**建好
     * （见 {@code config.SettingsConfig}），这里只当它是一个"前缀 → Object"的映射。
     *
     * <p>漏注册一个前缀的表现是「那一组配置项全部改不动，每条都报
     * 『不支持的配置前缀：X』」—— 表和它的解释现在都在 {@code SettingsConfig} 里。
     */
    private final Map<String, Object> roots;

    private final AtomicInteger applyCount = new AtomicInteger();

    public SettingsService(SettingsWhitelist whitelist, OverridesFile overrides, SettingsRoots roots) {
        this.whitelist = whitelist;
        this.overrides = overrides;
        this.roots = roots.byPrefix();
    }

    /** 仅供测试：白名单里的某个前缀有没有配置对象接管 */
    boolean supportsPrefix(String prefix) {
        return roots.containsKey(prefix);
    }

    /**
     * 仅供测试：这个 key 能不能真的解析到「对象 + 字段」。
     *
     * <p>比 {@link #supportsPrefix} 严一层 —— 前缀有人接管、路径却对不上，
     * 界面上照样是"看得见、改不动"。那正是多段前缀漏注册时的表现。
     */
    boolean canResolve(String key) {
        try {
            resolve(key);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public record ItemView(String key, String label, String group, String type,
                           String hint, Object value) {
    }

    /** 读当前全部可写配置的值 */
    public Map<String, List<ItemView>> current() {
        Map<String, List<ItemView>> out = new LinkedHashMap<>();
        for (Map.Entry<String, List<SettingsWhitelist.Item>> e : whitelist.grouped().entrySet()) {
            List<ItemView> list = new ArrayList<>();
            for (SettingsWhitelist.Item i : e.getValue()) {
                list.add(new ItemView(i.key(), i.label(), i.group(), i.type(), i.hint(),
                        readValue(i.key())));
            }
            out.put(e.getKey(), list);
        }
        return out;
    }

    /** 应用一批改动 */
    public ApplyResult apply(Map<String, Object> changes) {
        List<String> applied = new ArrayList<>();
        List<String> rejected = new ArrayList<>();
        Map<String, String> errors = new LinkedHashMap<>();

        // 规整过的值（不是原始入参）：写进对象和落盘必须是同一个值，
        // 否则「页面显示对了、重启又变回去」
        Map<String, Object> accepted = new LinkedHashMap<>();

        for (Map.Entry<String, Object> e : changes.entrySet()) {
            String key = e.getKey();
            if (!whitelist.isAllowed(key)) {
                rejected.add(key);
                log.warn("[SETTINGS] 拒绝修改未授权的配置项：{}", key);
                continue;
            }
            try {
                Object value = sanitize(key, e.getValue());
                writeValue(key, value);
                accepted.put(key, value);
                applied.add(key);
            } catch (Exception ex) {
                errors.put(key, ex.getMessage());
            }
        }

        if (!applied.isEmpty()) {
            // 交给 OverridesFile 统一写入 —— 它是该文件的唯一入口，
            // 保证不会因为"两边各写各的"而产生重复顶层键
            overrides.putAll(accepted);
            applyCount.addAndGet(applied.size());
            log.info("[SETTINGS] 已热生效 {} 项配置：{}", applied.size(), applied);
        }

        return new ApplyResult(errors.isEmpty() && rejected.isEmpty(), applied, rejected,
                errors, "已立即生效（无需重启）");
    }

    public record ApplyResult(boolean ok, List<String> applied, List<String> rejected,
                              Map<String, String> errors, String note) {
    }

    public int applyCount() {
        return applyCount.get();
    }

    /**
     * 写入前的规整。
     *
     * <p>群号只可能是整数：从 Excel / WPS 粘进来会变成 {@code 2.09127588E8}，
     * 落盘后因为带小数点又被当成 double 读回，公开站就显示成科学计数法。
     * 在写的时候就规范掉，存的和显示的就都是纯数字了。
     */
    private static Object sanitize(String key, Object value) {
        if ("app.site.group-number".equals(key) && value != null) {
            return com.example.qqbot.site.SitePolicy.normalizeGroupNumber(String.valueOf(value));
        }
        return value;
    }

    // ==================== 反射读写 ====================

    private Object readValue(String key) {
        try {
            ObjectHolder h = resolve(key);
            Field f = h.target.getClass().getDeclaredField(h.field);
            f.setAccessible(true);
            return f.get(h.target);
        } catch (Exception e) {
            log.debug("[SETTINGS] 读不到 {}：{}", key, e.getMessage());
            return null;
        }
    }

    private void writeValue(String key, Object value) throws Exception {
        ObjectHolder h = resolve(key);
        Field f = h.target.getClass().getDeclaredField(h.field);
        f.setAccessible(true);
        f.set(h.target, convert(f.getType(), value));
    }

    /**
     * 把 {@code app.<前缀...>.<字段>} 解析成「哪个对象 + 哪个字段」。
     *
     * <h2>前缀可以是多段，且**从长的开始试**</h2>
     * 配置根不止按第二段划分：{@code app.kb.map.*} 归 {@code MapOptions}，
     * 而 {@code app.kb.*} 的其余部分归 {@code KbProperties}。
     * 所以候选前缀要按长度从大到小试，先命中者赢 —— 反过来 "kb" 会把 "kb.map" 抢走，
     * 表现是**那一组配置项在界面上看得见、一改就报「配置路径不存在」**
     * （2026-10-15 实测：{@code app.kb.map.request-gap-ms} 等三项就是这么报的）。
     */
    private ObjectHolder resolve(String key) throws Exception {
        String[] parts = key.split("\\.");
        for (int cut = parts.length - 1; cut >= 2; cut--) {
            Object target = roots.get(String.join(".", java.util.Arrays.copyOfRange(parts, 1, cut)));
            if (target == null) {
                continue;
            }
            for (int i = cut; i < parts.length - 1; i++) {
                String getter = "get" + capitalize(camel(parts[i]));
                Object next;
                try {
                    next = target.getClass().getMethod(getter).invoke(target);
                } catch (NoSuchMethodException e) {
                    next = null;
                }
                if (next == null) {
                    throw new IllegalArgumentException("配置路径不存在：" + key);
                }
                target = next;
            }
            return new ObjectHolder(target, camel(parts[parts.length - 1]));
        }
        throw new IllegalArgumentException("不支持的配置前缀：" + parts[1]);
    }

    private record ObjectHolder(Object target, String field) {
    }

    private static String camel(String s) {
        StringBuilder sb = new StringBuilder();
        boolean up = false;
        for (char c : s.toCharArray()) {
            if (c == '-') {
                up = true;
            } else {
                sb.append(up ? Character.toUpperCase(c) : c);
                up = false;
            }
        }
        return sb.toString();
    }

    private static String capitalize(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private static Object convert(Class<?> type, Object value) {
        if (value == null) {
            return null;
        }
        if (type == boolean.class || type == Boolean.class) {
            return value instanceof Boolean b ? b : Boolean.parseBoolean(String.valueOf(value));
        }
        if (type == int.class || type == Integer.class) {
            return value instanceof Number n ? n.intValue() : Integer.parseInt(String.valueOf(value).trim());
        }
        if (type == long.class || type == Long.class) {
            return value instanceof Number n ? n.longValue() : Long.parseLong(String.valueOf(value).trim());
        }
        if (type == double.class || type == Double.class) {
            return value instanceof Number n ? n.doubleValue() : Double.parseDouble(String.valueOf(value).trim());
        }
        if (List.class.isAssignableFrom(type)) {
            List<String> out = new ArrayList<>();
            if (value instanceof List<?> l) {
                for (Object o : l) {
                    if (o != null && StringUtils.hasText(String.valueOf(o))) {
                        out.add(String.valueOf(o).trim());
                    }
                }
            } else if (StringUtils.hasText(String.valueOf(value))) {
                for (String line : String.valueOf(value).split("[\\n,，]")) {
                    if (StringUtils.hasText(line)) {
                        out.add(line.trim());
                    }
                }
            }
            return out;
        }
        return String.valueOf(value).trim();
    }
}
