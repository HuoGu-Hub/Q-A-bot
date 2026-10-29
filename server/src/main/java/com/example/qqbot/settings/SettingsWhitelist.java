package com.example.qqbot.settings;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * **可写配置的白名单** —— 这是配置中心安全的核心。
 *
 * <p>你明确说过「不能开放这么大权限，改坏了怎么办」——这个类就是那道闸门。
 * 只有列在这里的配置项才允许从界面修改；**其余一律拒绝**，
 * 即使有人伪造请求改 §app.persistence.db§ 也会被挡掉。
 *
 * <p>分类原则：
 * <ul>
 *   <li>🟢 **可写** —— 纯业务行为（话术、开关、限流数值），改错最多是行为不对，不会丢数据</li>
 *   <li>🔴 **禁写** —— 基础设施（端口、数据库路径、线程池）与密钥，改错会坏系统或泄密</li>
 * </ul>
 */
@Component
public class SettingsWhitelist {

    /** 一项可写配置的定义 */
    public record Item(String key, String label, String group, String type,
                       String hint, Object defaultHint) {
    }

    /**
     * 分组展示顺序。
     * type: bool / int / string / text / enum / list
     */
    public static final List<Item> ITEMS = List.of(
            // ---------- 回复行为 ----------
            new Item("app.guard.access.require-mention-in-group", "群里必须 @", "回复行为",
                    "bool", "", true),
            new Item("app.guard.access.private-chat-policy", "私聊策略", "回复行为",
                    "enum", "all=都回 / whitelist=只回白名单 / off=不回", "off"),
            new Item("app.guard.access.private-whitelist", "私聊白名单", "回复行为",
                    "list", "一行一个 QQ 号", List.of()),
            new Item("app.guard.access.group-blacklist", "群黑名单", "回复行为",
                    "list", "一行一个群号", List.of()),
            new Item("app.guard.access.user-blacklist", "用户黑名单", "回复行为",
                    "list", "一行一个 QQ 号", List.of()),

            // ---------- 频率限制 ----------
            new Item("app.guard.rate-limit.enabled", "启用限流", "频率限制",
                    "bool", "", true),
            new Item("app.guard.rate-limit.per-group-per-minute", "每群每分钟", "频率限制",
                    "int", "", 10),
            new Item("app.guard.rate-limit.per-user-per-minute", "每人每分钟", "频率限制",
                    "int", "", 1),
            new Item("app.guard.rate-limit.per-user-window-seconds", "用户限流窗口（秒）", "频率限制",
                    "int", "", 60),
            new Item("app.guard.rate-limit.on-limit", "超限处理", "频率限制",
                    "enum", "silent=不出声 / notify-once=提示一次", "notify-once"),

            // ---------- 话术 ----------
            new Item("app.guard.rate-limit.notify-user-text", "用户限流提示", "话术",
                    "text", "支持 {seconds} = 生效窗口秒数", ""),
            new Item("app.guard.rate-limit.notify-group-text", "群限流提示", "话术",
                    "text", "", ""),
            new Item("app.guard.rate-limit.queue-timeout-text", "排队超时提示", "话术",
                    "text", "", ""),
            new Item("app.guard.content-gate.no-text-reply", "纯表情兜底", "话术",
                    "text", "", ""),
            new Item("app.guard.content-gate.no-vision-reply", "看不了图时", "话术",
                    "text", "", ""),
            new Item("app.guard.words.inbound-refusal-text", "敏感词拒绝", "话术",
                    "text", "支持 {word}", ""),
            new Item("app.guard.words.outbound-fallback-text", "出站兜底", "话术",
                    "text", "支持 {word}", ""),

            // ---------- 成本预算 ----------
            new Item("app.guard.budget.enabled", "启用成本预算", "成本预算",
                    "bool", "", true),
            new Item("app.guard.budget.per-user-per-day", "每人每日次数", "成本预算",
                    "int", "0 = 不限", 0),
            new Item("app.guard.budget.per-group-per-day", "每群每日次数", "成本预算",
                    "int", "0 = 不限", 0),
            new Item("app.guard.budget.global-per-day", "全局每日次数", "成本预算",
                    "int", "0 = 不限", 0),
            new Item("app.guard.budget.global-tokens-per-day", "全局每日 token", "成本预算",
                    "int", "0 = 不限", 0),
            new Item("app.guard.budget.user-limit-text", "额度用尽提示", "成本预算",
                    "text", "支持 {limit} = 该用户当日额度", ""),

            // ---------- 出站节奏 ----------
            new Item("app.guard.outbound.enabled", "启用出站节奏", "出站节奏",
                    "bool", "", true),
            new Item("app.guard.outbound.group-min-interval-millis", "同群最小间隔（毫秒）", "出站节奏",
                    "int", "", 500),
            new Item("app.guard.outbound.group-jitter-min-millis", "同群抖动下限（毫秒）", "出站节奏",
                    "int", "可以为负", -100),
            new Item("app.guard.outbound.group-jitter-max-millis", "同群抖动上限（毫秒）", "出站节奏",
                    "int", "", 400),
            new Item("app.guard.outbound.global-min-interval-millis", "账号级最小间隔（毫秒）", "出站节奏",
                    "int", "0 = 关", 500),
            new Item("app.guard.outbound.global-jitter-min-millis", "账号级抖动下限（毫秒）", "出站节奏",
                    "int", "可以为负", -100),
            new Item("app.guard.outbound.global-jitter-max-millis", "账号级抖动上限（毫秒）", "出站节奏",
                    "int", "", 400),
            new Item("app.guard.outbound.max-chars-per-message", "单条字数上限", "出站节奏",
                    "int", "0 = 不切", 300),
            new Item("app.guard.outbound.forward-merged", "长回复合并转发", "出站节奏",
                    "bool", "", true),
            new Item("app.guard.outbound.forward-min-parts", "合并转发 · 段数超过", "出站节奏",
                    "int", "0 = 不看段数", 2),
            new Item("app.guard.outbound.forward-min-chars", "合并转发 · 总字数超过", "出站节奏",
                    "int", "0 = 不看字数", 0),
            new Item("app.guard.outbound.forward-intro-text", "合并转发前的提示语", "出站节奏",
                    "text", "支持 {parts}", ""),

            // ---------- 首页轮播 ----------
            new Item("app.site.carousel.enabled", "启用首页轮播", "首页轮播",
                    "bool", "", true),
            new Item("app.site.carousel.interval-ms", "切换间隔（毫秒）", "首页轮播",
                    "int", "小于 1000 按 1000 算", 4000),
            new Item("app.site.carousel.max-count", "最多几张", "首页轮播",
                    "int", "", 8),
            new Item("app.site.carousel.max-size-kb", "单张大小上限（KB）", "首页轮播",
                    "int", "", 2048),

            // ---------- 回复风格 ----------
            new Item("app.llm.reply-style.enabled", "启用风格约束", "回复风格",
                    "bool", "", true),
            new Item("app.llm.reply-style.max-chars", "正文长度上限（字）", "回复风格",
                    "int", "0 = 不限", 0),
            new Item("app.llm.reply-style.format", "格式", "回复风格",
                    "enum", "default=不管 / plain=禁列表标题 / list=鼓励短列表", "default"),
            new Item("app.llm.reply-style.include-sources", "附 Wiki 来源链接", "回复风格",
                    "bool", "", false),
            new Item("app.llm.reply-style.tone", "语气", "回复风格",
                    "text", "例：轻松、简洁，像群友聊天", ""),

            // ---------- 功能开关 ----------
            new Item("app.guard.enabled", "安全中间层", "功能开关",
                    "bool", "", true),
            new Item("app.guard.kill-switch", "应急停机", "功能开关",
                    "bool", "⚠️ 打开后机器人完全不回复", false),
            new Item("app.kb.enabled", "知识库检索", "功能开关",
                    "bool", "", true),
            // 注意：它只管"记不记录问答"，**不再关掉数据库** ——
            // 库的开关是 app.persistence.enabled，但那个**故意不进白名单**：
            // 关掉它会让指令 / 词条 / 轮播一起失效，不该是个点一下就生效的开关。
            new Item("app.qa.enabled", "问答记录", "功能开关",
                    "bool", "", true),
            new Item("app.logs.mask-sensitive", "日志脱敏", "功能开关",
                    "bool", "", true),

            // ---------- 指令系统 ----------
            new Item("app.commands.enabled", "启用指令", "指令系统",
                    "bool", "", true),
            new Item("app.commands.rate-limit-per-minute", "命令每分钟上限", "指令系统",
                    "int", "", 10),
            new Item("app.commands.allow-user-ids", "允许 {user.id}", "指令系统",
                    "bool", "⚠️ 开启后回复里会打出 QQ 号", false),

            // ---------- 媒体 ----------
            new Item("app.media.max-images-per-message", "单条消息图片上限", "媒体",
                    "int", "", 3),
            new Item("app.media.temp-images.retention-hours", "临时图保留时长", "媒体",
                    "int", "单位小时", 24),
            new Item("app.media.temp-images.max-total-size-mb", "图片缓存上限", "媒体",
                    "int", "单位 MB", 200),
            new Item("app.media.outbound-images.enabled", "允许回复带图", "媒体",
                    "bool", "", true),
            new Item("app.media.outbound-images.max-per-reply", "单条回复配图上限", "媒体",
                    "int", "0 = 不限制", 2),
            new Item("app.media.outbound-images.max-size-kb", "配图大小上限", "媒体",
                    "int", "单位 KB", 2048),

            // ---------- 知识库 ----------
            new Item("app.kb.top-k", "检索条数", "知识库",
                    "int", "", 5),
            new Item("app.kb.min-score", "相似度阈值", "知识库",
                    "string", "0~1", 0.45),
            new Item("app.kb.public-search.vector-enabled", "公开站语义搜索", "知识库",
                    "bool", "", true),
            new Item("app.kb.public-search.vector-daily-limit", "语义搜索每日上限", "知识库",
                    "int", "", 800),
            new Item("app.kb.map.shot.enabled", "地点问答配图", "知识库",
                    "bool", "", true),
            new Item("app.kb.map.shot.deadline-ms", "配图取图预算", "知识库",
                    "int", "单位毫秒", 2000),
            new Item("app.kb.map.shot.label-max", "配图标几个名字", "知识库",
                    "int", "0 = 不标", 4),
            new Item("app.kb.map.request-gap-ms", "取图出站间隔", "知识库",
                    "int", "单位毫秒", 6000),

            // ---------- 机器人身份 ----------
            // ⚠️ 只放**真的有人读**的项 —— 放进来却没人读的配置会变成
            //    「改了没反应」的假开关，比少一个开关更难排查。
            new Item("app.bot.name", "机器人昵称", "机器人身份",
                    "string", "", "Q-A-bot"),
            new Item("app.bot.org", "运营方 / 组织名", "机器人身份",
                    "string", "", ""),
            new Item("app.bot.author", "技术支持署名", "机器人身份",
                    "string", "", ""),

            // ---------- 站点信息 ----------
            new Item("app.site.group-name", "群名称", "站点信息",
                    "string", "", "示例玩家群"),
            new Item("app.site.group-number", "群号", "站点信息",
                    "string", "", "100000004"),
            new Item("app.site.group-desc", "群说明", "站点信息",
                    "text", "", ""),
            new Item("app.site.join-hint", "加群提示", "站点信息",
                    "text", "如「群号搜索」或「扫码」", ""),

            // ---------- 统计 ----------
            new Item("app.qa.retention-days", "原文保留天数", "统计",
                    "int", "0 = 永不删除", 180)
    );

    private static final Set<String> ALLOWED = ITEMS.stream()
            .map(Item::key)
            .collect(java.util.stream.Collectors.toSet());

    /** 某个 key 是否允许从界面修改 */
    public boolean isAllowed(String key) {
        return key != null && ALLOWED.contains(key);
    }

    /** 按分组归类（给前端渲染用） */
    public Map<String, List<Item>> grouped() {
        Map<String, List<Item>> out = new LinkedHashMap<>();
        for (Item i : ITEMS) {
            out.computeIfAbsent(i.group(), k -> new java.util.ArrayList<>()).add(i);
        }
        return out;
    }
}
