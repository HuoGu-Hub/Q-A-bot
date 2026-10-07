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
                    "bool", "关掉后群里不 @ 也会回答（不建议）", true),
            new Item("app.guard.access.private-chat-policy", "私聊策略", "回复行为",
                    "enum", "all=都回 / whitelist=只回白名单 / off=不回", "off"),
            new Item("app.guard.access.private-whitelist", "私聊白名单", "回复行为",
                    "list", "一行一个 QQ 号", List.of()),
            new Item("app.guard.access.group-blacklist", "群黑名单", "回复行为",
                    "list", "一行一个群号，名单里的群永不响应", List.of()),
            new Item("app.guard.access.user-blacklist", "用户黑名单", "回复行为",
                    "list", "一行一个 QQ 号，这些人永不响应", List.of()),

            // ---------- 频率限制 ----------
            new Item("app.guard.rate-limit.enabled", "启用限流", "频率限制",
                    "bool", "关掉后可被刷屏", true),
            new Item("app.guard.rate-limit.per-group-per-minute", "每群每分钟", "频率限制",
                    "int", "整个群每分钟最多被回复几条", 10),
            new Item("app.guard.rate-limit.per-user-per-minute", "每人每分钟", "频率限制",
                    "int", "同一个人在同一群里每分钟最多几次", 1),
            new Item("app.guard.rate-limit.per-user-window-seconds", "用户限流窗口（秒）", "频率限制",
                    "int", "和「每人每分钟」配合：每 N 秒最多 M 次；群维度固定 60 秒", 60),
            new Item("app.guard.rate-limit.on-limit", "超限处理", "频率限制",
                    "enum", "silent=不出声 / notify-once=提示一次", "notify-once"),

            // ---------- 话术 ----------
            new Item("app.guard.rate-limit.notify-user-text", "用户限流提示", "话术",
                    "text", "支持 {seconds} = 生效窗口秒数", ""),
            new Item("app.guard.rate-limit.notify-group-text", "群限流提示", "话术",
                    "text", "", ""),
            new Item("app.guard.rate-limit.queue-timeout-text", "排队超时提示", "话术",
                    "text", "排队太久被丢弃时回的话", ""),
            new Item("app.guard.content-gate.no-text-reply", "纯表情兜底", "话术",
                    "text", "消息里既没文字也没图片时回的话", ""),
            new Item("app.guard.content-gate.no-vision-reply", "看不了图时", "话术",
                    "text", "有图片但当前模型不支持视觉时", ""),
            new Item("app.guard.words.inbound-refusal-text", "敏感词拒绝", "话术",
                    "text", "支持 {word} 回显命中的词", ""),
            new Item("app.guard.words.outbound-fallback-text", "出站兜底", "话术",
                    "text", "支持 {word}", ""),

            // ---------- 成本预算 ----------
            new Item("app.guard.budget.enabled", "启用成本预算", "成本预算",
                    "bool", "关掉后所有额度判定都不生效", true),
            new Item("app.guard.budget.per-user-per-day", "每人每日次数", "成本预算",
                    "int", "0 = 不限。超了回提示", 0),
            new Item("app.guard.budget.per-group-per-day", "每群每日次数", "成本预算",
                    "int", "0 = 不限。超了该群静默", 0),
            new Item("app.guard.budget.global-per-day", "全局每日次数", "成本预算",
                    "int", "0 = 不限。超了熔断并告警", 0),
            new Item("app.guard.budget.global-tokens-per-day", "全局每日 token", "成本预算",
                    "int", "0 = 不限。按输入+输出合计", 0),
            new Item("app.guard.budget.user-limit-text", "额度用尽提示", "成本预算",
                    "text", "支持 {limit} = 该用户当日额度", ""),

            // ---------- 出站节奏 ----------
            new Item("app.guard.outbound.enabled", "启用出站节奏", "出站节奏",
                    "bool", "关掉 = 长回复不分片、连发不留间隔", true),
            new Item("app.guard.outbound.group-min-interval-millis", "同群最小间隔（毫秒）", "出站节奏",
                    "int", "同一个群两条消息之间至少隔多久", 800),
            new Item("app.guard.outbound.group-jitter-millis", "间隔抖动（毫秒）", "出站节奏",
                    "int", "在最小间隔上再随机 ±这个值，避免机械感", 400),
            new Item("app.guard.outbound.max-chars-per-message", "单条字数上限", "出站节奏",
                    "int", "超过就按句子切成多条；0 = 不切", 300),
            new Item("app.guard.outbound.forward-merged", "长回复合并转发", "出站节奏",
                    "bool", "切成 3 段以上时打包成一条「合并转发」，群里只算一次发言；"
                            + "协议端不支持会自动退回逐条发", true),
            new Item("app.guard.outbound.forward-min-parts", "合并转发 · 段数超过", "出站节奏",
                    "int", "切成的段数超过它才打包（默认 2 = 3 段起）。0 = 不看段数", 2),
            new Item("app.guard.outbound.forward-min-chars", "合并转发 · 总字数超过", "出站节奏",
                    "int", "整条回复的字数超过它才打包。0 = 不看字数（默认）。和上面那条**都要满足**", 0),
            new Item("app.guard.outbound.forward-intro-text", "合并转发前的提示语", "出站节奏",
                    "text", "只发在群里。支持 {parts} = 分了几段。卡片本身没法 @ 人，靠它提醒提问的人", ""),

            // ---------- 首页轮播 ----------
            new Item("app.site.carousel.enabled", "启用首页轮播", "首页轮播",
                    "bool", "关掉后公开站首页不显示轮播（图还在，随时能开回来）", true),
            new Item("app.site.carousel.interval-ms", "切换间隔（毫秒）", "首页轮播",
                    "int", "自动切下一张的间隔，小于 1000 按 1000 算", 4000),
            new Item("app.site.carousel.max-count", "最多几张", "首页轮播",
                    "int", "到上限后管理端会拒绝上传，得先删一张", 8),
            new Item("app.site.carousel.max-size-kb", "单张大小上限（KB）", "首页轮播",
                    "int", "超过就拒绝上传。注意 nginx 的 client_max_body_size 也要够大", 2048),

            // ---------- 回复风格 ----------
            new Item("app.llm.reply-style.enabled", "启用风格约束", "回复风格",
                    "bool", "关掉 = 完全按模型自己的习惯回", true),
            new Item("app.llm.reply-style.max-chars", "正文长度上限（字）", "回复风格",
                    "int", "0 = 不限。这是给模型的要求，不是硬截断", 0),
            new Item("app.llm.reply-style.format", "格式", "回复风格",
                    "enum", "default=不管 / plain=禁列表标题 / list=鼓励短列表", "default"),
            new Item("app.llm.reply-style.include-sources", "附 Wiki 来源链接", "回复风格",
                    "bool", "用了检索资料时在结尾附一行来源", false),
            new Item("app.llm.reply-style.tone", "语气", "回复风格",
                    "text", "自由文本。例：轻松、简洁，像群友聊天", ""),

            // ---------- 功能开关 ----------
            new Item("app.guard.enabled", "安全中间层", "功能开关",
                    "bool", "关掉后所有过滤失效（仅调试用）", true),
            new Item("app.guard.kill-switch", "应急停机", "功能开关",
                    "bool", "⚠️ 打开后机器人完全不回复", false),
            new Item("app.kb.enabled", "知识库检索", "功能开关",
                    "bool", "关掉后退化成没有知识库的普通回答", true),
            // 注意：它只管"记不记录问答"，**不再关掉数据库** ——
            // 库的开关是 app.persistence.enabled，但那个**故意不进白名单**：
            // 关掉它会让指令 / 词条 / 轮播一起失效，不该是个点一下就生效的开关。
            new Item("app.qa.enabled", "问答记录", "功能开关",
                    "bool", "关掉后不记录问答（库仍然开着，其他功能不受影响）", true),
            new Item("app.logs.mask-sensitive", "日志脱敏", "功能开关",
                    "bool", "把 token/QQ号 等替换掉", true),

            // ---------- 指令系统 ----------
            new Item("app.commands.enabled", "启用指令", "指令系统",
                    "bool", "关掉后 /命令 不再触发", true),
            new Item("app.commands.require-mention", "命令必须 @", "指令系统",
                    "bool", "建议保持开启，否则群里贴 /路径 会误触发", true),
            new Item("app.commands.rate-limit-per-minute", "命令每分钟上限", "指令系统",
                    "int", "每人每分钟最多触发几次指令", 10),
            new Item("app.commands.allow-user-ids", "允许 {user.id}", "指令系统",
                    "bool", "⚠️ 开启后回复里会打出 QQ 号", false),

            // ---------- 媒体 ----------
            new Item("app.media.max-images-per-message", "单条消息图片上限", "媒体",
                    "int", "超出的只记日志不回话（内存保护）", 3),
            new Item("app.media.temp-images.retention-hours", "临时图保留时长", "媒体",
                    "int", "单位小时", 24),
            new Item("app.media.temp-images.max-total-size-mb", "图片缓存上限", "媒体",
                    "int", "单位 MB，超出按最后使用时间淘汰", 200),

            // ---------- 知识库 ----------
            new Item("app.kb.top-k", "检索条数", "知识库",
                    "int", "带进 prompt 的资料条数", 5),
            new Item("app.kb.min-score", "相似度阈值", "知识库",
                    "string", "低于它的资料宁可不要（0~1）", 0.45),
            new Item("app.kb.public-search.vector-enabled", "公开站语义搜索", "知识库",
                    "bool", "开=能搜到中文口语提问（每次调一次向量模型，费用极低）；关=只做本地关键词", true),
            new Item("app.kb.public-search.vector-daily-limit", "语义搜索每日上限", "知识库",
                    "int", "超过后当天自动降级为关键词搜索，不会报错", 800),

            // ---------- 机器人身份 ----------
            // ⚠️ 只放真的有人读的项。org / author 目前全项目没有调用点，
            //    放进来只会变成「改了没反应」的假开关 —— 等真接上再加。
            new Item("app.bot.name", "机器人昵称", "机器人身份",
                    "string", "用在 {bot} 变量和限流提示话术里；改完立即生效", "示例助手"),

            // ---------- 站点信息 ----------
            new Item("app.site.group-name", "群名称", "站点信息",
                    "string", "显示在「关于」页", "示例玩家群"),
            new Item("app.site.group-number", "群号", "站点信息",
                    "string", "显示在「关于」页，留空则隐藏", "100000004"),
            new Item("app.site.group-desc", "群说明", "站点信息",
                    "text", "介绍这个群是干什么的", ""),
            new Item("app.site.join-hint", "加群提示", "站点信息",
                    "text", "怎么加群（如「群号搜索」或「扫码」）", ""),

            // ---------- 统计 ----------
            new Item("app.qa.retention-days", "原文保留天数", "统计",
                    "int", "0=永不删除（统计表永远不删）", 180)
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
