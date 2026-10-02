package com.example.qqbot.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * 安全中间层的全部配置，对应 application.yml 里的 app.guard.*
 *
 * <p>设计原则：<b>每一层都能单独开关</b>，出问题时可以逐层关闭来定位。
 */
@ConfigurationProperties(prefix = "app.guard")
public class GuardProperties {

    /** 总开关。关掉后所有限制失效（调试用） */
    private boolean enabled = true;

    /**
     * 应急开关。设为 true 后机器人**完全不回复**（连兜底词都不回）。
     * 出事的时候你需要一条命令让它立刻闭嘴。
     */
    private boolean killSwitch = false;

    private Access access = new Access();
    private RateLimit rateLimit = new RateLimit();
    private Outbound outbound = new Outbound();
    private Budget budget = new Budget();
    private ContentGate contentGate = new ContentGate();
    private Words words = new Words();
    private FileAccess fileAccess = new FileAccess();

    // ==================== 准入控制 ====================

    public static class Access {

        /** 群聊里是否必须 @ 机器人才响应 */
        private boolean requireMentionInGroup = true;

        /** 私聊策略：all / whitelist / off。默认 off —— 只允许在群里聊 */
        private String privateChatPolicy = "off";

        /** 私聊白名单（privateChatPolicy = whitelist 时生效） */
        private List<Long> privateWhitelist = new ArrayList<>();

        /** 群黑名单：名单里的群永不响应 */
        private List<Long> groupBlacklist = new ArrayList<>();

        /** 用户黑名单：这些人永不响应 */
        private List<Long> userBlacklist = new ArrayList<>();

        public boolean isRequireMentionInGroup() {
            return requireMentionInGroup;
        }

        public void setRequireMentionInGroup(boolean requireMentionInGroup) {
            this.requireMentionInGroup = requireMentionInGroup;
        }

        public String getPrivateChatPolicy() {
            return privateChatPolicy;
        }

        public void setPrivateChatPolicy(String privateChatPolicy) {
            this.privateChatPolicy = privateChatPolicy;
        }

        public List<Long> getPrivateWhitelist() {
            return privateWhitelist;
        }

        public void setPrivateWhitelist(List<Long> privateWhitelist) {
            this.privateWhitelist = privateWhitelist;
        }

        public List<Long> getGroupBlacklist() {
            return groupBlacklist;
        }

        public void setGroupBlacklist(List<Long> groupBlacklist) {
            this.groupBlacklist = groupBlacklist;
        }

        public List<Long> getUserBlacklist() {
            return userBlacklist;
        }

        public void setUserBlacklist(List<Long> userBlacklist) {
            this.userBlacklist = userBlacklist;
        }
    }

    // ==================== 频率限制 ====================

    public static class RateLimit {

        private boolean enabled = true;

        /** 同一个群每分钟最多被回复几条 */
        private int perGroupPerMinute = 10;

        /** 同一个人每分钟最多被回复几次（跨群统计） */
        private int perUserPerMinute = 1;

        /**
         * 用户维度的滑动窗口长度（秒）—— 和 {@link #perUserPerMinute} 是乘数关系：
         * 「每 {@code perUserWindowSeconds} 秒最多 {@code perUserPerMinute} 次」。
         *
         * <p>默认 60。想回得快一点就调小（比如 20 = 每 20 秒回一次）。
         * 群维度固定 60 秒，不受这项影响。
         */
        private int perUserWindowSeconds = 60;

        /**
         * 被限流之后怎么办：
         * silent        —— 完全不出声（最防炸群）
         * notify-once   —— 每个用户每个冷却周期内提示一次，之后静默（默认）
         */
        private String onLimit = "notify-once";

        /** 用户级限流（同一个人在同一个群里刷太快）时回的话。{seconds} = 生效窗口秒数 */
        private String notifyUserText = "别急呀，同一个人我 {seconds} 秒只能回一次，稍等一下下～";

        /** 群级限流（整个群被回复太频繁）时回的话 */
        private String notifyGroupText = "这个群我有点忙不过来啦，一分钟内已经回了不少，我等会儿再看～";

        /**
         * 任务在队列里等了太久、被丢弃时回的话。
         * 和上面两句区分开，让用户知道「不是限制你，是刚才太挤了」。
         */
        private String queueTimeoutText = "刚才排队太久啦，你再说一遍吧～";

        /** 提示话术的每个用户冷却时间（秒）——防止「提示」本身变成刷屏 */
        private int notifyCooldownSeconds = 300;

        /**
         * 提示话术的**群级**上限：同一个群每分钟最多回几条提示。
         * 这一项很关键：2000 人的群里 50 个人同时被限流，
         * 如果每人都回一句提示，就等于用「提示」把群刷爆了。
         */
        private int notifyGroupPerMinute = 3;

        public String getNotifyUserText() {
            return notifyUserText;
        }

        public void setNotifyUserText(String notifyUserText) {
            this.notifyUserText = notifyUserText;
        }

        public String getNotifyGroupText() {
            return notifyGroupText;
        }

        public void setNotifyGroupText(String notifyGroupText) {
            this.notifyGroupText = notifyGroupText;
        }

        public String getQueueTimeoutText() {
            return queueTimeoutText;
        }

        public void setQueueTimeoutText(String queueTimeoutText) {
            this.queueTimeoutText = queueTimeoutText;
        }

        public int getNotifyGroupPerMinute() {
            return notifyGroupPerMinute;
        }

        public void setNotifyGroupPerMinute(int notifyGroupPerMinute) {
            this.notifyGroupPerMinute = notifyGroupPerMinute;
        }

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public int getPerGroupPerMinute() {
            return perGroupPerMinute;
        }

        public void setPerGroupPerMinute(int perGroupPerMinute) {
            this.perGroupPerMinute = perGroupPerMinute;
        }

        public int getPerUserPerMinute() {
            return perUserPerMinute;
        }

        public void setPerUserPerMinute(int perUserPerMinute) {
            this.perUserPerMinute = perUserPerMinute;
        }

        public int getPerUserWindowSeconds() {
            return perUserWindowSeconds;
        }

        public void setPerUserWindowSeconds(int perUserWindowSeconds) {
            this.perUserWindowSeconds = perUserWindowSeconds;
        }

        public String getOnLimit() {
            return onLimit;
        }

        public void setOnLimit(String onLimit) {
            this.onLimit = onLimit;
        }

        public int getNotifyCooldownSeconds() {
            return notifyCooldownSeconds;
        }

        public void setNotifyCooldownSeconds(int notifyCooldownSeconds) {
            this.notifyCooldownSeconds = notifyCooldownSeconds;
        }
    }

    // ==================== 成本预算（D6） ====================

    /**
     * 成本预算 —— 管的是「今天总共能用多少」，和 {@link RateLimit}（管「多快」）互补。
     *
     * <p>为什么光有限流不够：限流挡不住「细水长流」。每人每分钟 1 次听着很克制，
     * 一天也能问 1440 次；群里人多一点，token 账单就起来了。所以需要一层
     * <b>额度</b>（按天归零），而不是只有频率。
     *
     * <p>所有额度都是 <b>0 = 不限</b>，所以默认配置完全不改变现有行为。
     */
    public static class Budget {

        private boolean enabled = true;

        /** 每个用户每天最多被回复几次。0 = 不限 */
        private int perUserPerDay = 0;

        /** 每个群每天最多被回复几次。0 = 不限。超了该群<b>静默</b> */
        private int perGroupPerDay = 0;

        /** 全局每天最多回复几次。0 = 不限。超了<b>熔断</b>（全都不回 + 告警） */
        private int globalPerDay = 0;

        /** 全局每天最多消耗多少 token（输入+输出）。0 = 不限 */
        private long globalTokensPerDay = 0;

        /** 单用户触顶时回的话。{limit} = 该用户当日额度 */
        private String userLimitText = "今天你已经问过我 {limit} 次啦，明天再来找我玩吧～";


        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public int getPerUserPerDay() {
            return perUserPerDay;
        }

        public void setPerUserPerDay(int perUserPerDay) {
            this.perUserPerDay = perUserPerDay;
        }

        public int getPerGroupPerDay() {
            return perGroupPerDay;
        }

        public void setPerGroupPerDay(int perGroupPerDay) {
            this.perGroupPerDay = perGroupPerDay;
        }

        public int getGlobalPerDay() {
            return globalPerDay;
        }

        public void setGlobalPerDay(int globalPerDay) {
            this.globalPerDay = globalPerDay;
        }

        public long getGlobalTokensPerDay() {
            return globalTokensPerDay;
        }

        public void setGlobalTokensPerDay(long globalTokensPerDay) {
            this.globalTokensPerDay = globalTokensPerDay;
        }

        public String getUserLimitText() {
            return userLimitText;
        }

        public void setUserLimitText(String userLimitText) {
            this.userLimitText = userLimitText;
        }
    }

    // ==================== 出站节奏（D7） ====================

    /**
     * 出站节奏 —— 管的是「发得多快」，和 {@link RateLimit}（管「回不回」）是两回事。
     *
     * <p>为什么单独立一项：限流放行之后，一条回复如果紧跟着第二条发出去，观感上
     * 还是刷屏；尤其是长回复被切成多条时，没有间隔的话 QQ 端会吞消息或折叠显示。
     */
    public static class Outbound {

        /** 总开关 */
        private boolean enabled = true;

        /** 同一个群两条消息之间的最小间隔（毫秒） */
        private long groupMinIntervalMillis = 800;

        /**
         * 间隔上的随机抖动（毫秒，±这个值）。
         * 固定间隔一眼就能看出是机器人；加抖动更像人在打字。
         */
        private long groupJitterMillis = 400;

        /** 单条消息的软上限（字），超过就按句子切成多条。0 = 不切 */
        private int maxCharsPerMessage = 300;

        /**
         * 切成多条时，是否把它们打包成**一条合并转发**（QQ 的「聊天记录」）发出去。
         *
         * <p>为什么值得做：群里有发言频率上限，一条长攻略被切成 8 条就是 8 次发言，
         * 几个人同时问就很容易撞上限、消息发不出去。合并转发在群里**只算一次发言**，
         * 点开还是一段段读，阅读体验不变。
         *
         * <p>失败会自动退回逐条发送，所以默认开着是安全的（见 OutboundSender）。
         */
        private boolean forwardMerged = true;

        /**
         * 触发合并转发的**段数**下限：切成的段数**超过**它才打包。
         *
         * <p>默认 2 → 也就是 3 段起。为什么是 3 起：卡片本身没法 @ 人，前面得先发一条带 @ 的
         * 提示语，所以「提示 + 卡片」= 2 条；只有 2 段时合并等于没省，3 段起才是净赚。
         *
         * <p>0 = 不看段数（只由 {@link #forwardMinChars} 决定）。
         */
        private int forwardMinParts = 2;

        /**
         * 触发合并转发的**总字数**下限：整条回复超过这么多字才打包。
         *
         * <p>0 = 不看字数（默认）。
         *
         * <p>和 {@link #forwardMinParts} 是**与**的关系：两个都满足才合并。
         */
        private int forwardMinChars = 0;

        /**
         * 合并转发前面那条提示语（群聊里带 @ 提问者）。
         *
         * <p>为什么要单独发一条：合并转发卡片本身没法 @ 人 —— 不进卡片就不知道是回给他的。
         * 支持 {@code {parts}} = 一共分了几段。
         */
        private String forwardIntroText = "答案有点长，我打包成一条聊天记录啦，点开就能看～";

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public long getGroupMinIntervalMillis() {
            return groupMinIntervalMillis;
        }

        public void setGroupMinIntervalMillis(long groupMinIntervalMillis) {
            this.groupMinIntervalMillis = groupMinIntervalMillis;
        }

        public long getGroupJitterMillis() {
            return groupJitterMillis;
        }

        public void setGroupJitterMillis(long groupJitterMillis) {
            this.groupJitterMillis = groupJitterMillis;
        }

        public int getMaxCharsPerMessage() {
            return maxCharsPerMessage;
        }

        public void setMaxCharsPerMessage(int maxCharsPerMessage) {
            this.maxCharsPerMessage = maxCharsPerMessage;
        }

        public boolean isForwardMerged() {
            return forwardMerged;
        }

        public void setForwardMerged(boolean forwardMerged) {
            this.forwardMerged = forwardMerged;
        }

        public int getForwardMinParts() {
            return forwardMinParts;
        }

        public void setForwardMinParts(int forwardMinParts) {
            this.forwardMinParts = forwardMinParts;
        }

        public int getForwardMinChars() {
            return forwardMinChars;
        }

        public void setForwardMinChars(int forwardMinChars) {
            this.forwardMinChars = forwardMinChars;
        }

        public String getForwardIntroText() {
            return forwardIntroText;
        }

        public void setForwardIntroText(String forwardIntroText) {
            this.forwardIntroText = forwardIntroText;
        }
    }

    // ==================== 内容门控 ====================

    public static class ContentGate {

        private boolean enabled = true;

        /** 消息里既没有文字也没有图片（纯表情 / 语音）时，不调模型，直接回这句 */
        private String noTextReply = "我看到啦，不过只有表情我不太懂，打字跟我说吧～";

        /** 有图片、但当前没有配置能看图的模型时回这句 */
        private String noVisionReply = "图片我这边暂时看不了，你打字形容一下？";

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getNoTextReply() {
            return noTextReply;
        }

        public void setNoTextReply(String noTextReply) {
            this.noTextReply = noTextReply;
        }

        public String getNoVisionReply() {
            return noVisionReply;
        }

        public void setNoVisionReply(String noVisionReply) {
            this.noVisionReply = noVisionReply;
        }
    }

    // ==================== 关键词表 ====================

    public static class Words {

        private boolean enabled = true;

        /**
         * 入站词表位置。支持两种前缀：
         * classpath:words/inbound.txt   —— 打包进 jar，改完要重新编译
         * file:./words/inbound.txt      —— 外部文件，改完重启即可
         */
        private String inboundFile = "classpath:words/inbound.txt";

        /** 出站词表位置（比入站宽松） */
        private String outboundFile = "classpath:words/outbound.txt";

        /** 入站命中时回复的拒绝话术（不调用大模型） */
        private String inboundRefusalText = "这个话题我不太方便聊，我们换个别的吧～";

        /** 出站命中时，把整条回复替换成这句 */
        private String outboundFallbackText = "我好像要说不该说的了，我们换个话题吧～";

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getInboundFile() {
            return inboundFile;
        }

        public void setInboundFile(String inboundFile) {
            this.inboundFile = inboundFile;
        }

        public String getOutboundFile() {
            return outboundFile;
        }

        public void setOutboundFile(String outboundFile) {
            this.outboundFile = outboundFile;
        }

        public String getInboundRefusalText() {
            return inboundRefusalText;
        }

        public void setInboundRefusalText(String inboundRefusalText) {
            this.inboundRefusalText = inboundRefusalText;
        }

        public String getOutboundFallbackText() {
            return outboundFallbackText;
        }

        public void setOutboundFallbackText(String outboundFallbackText) {
            this.outboundFallbackText = outboundFallbackText;
        }
    }

    // ==================== 文件访问守卫 ====================

    public static class FileAccess {

        private boolean enabled = true;

        /**
         * 禁止读取的文件尾缀。
         * 这一项是**预留**的：等以后加了「读文件」的工具，
         * 那个工具必须先调用 PathGuard 再读，否则形同虚设。
         */
        private List<String> blockedExtensions = new ArrayList<>(List.of(
                ".env", ".key", ".pem", ".p12", ".jks", ".keystore",
                ".sqlite", ".db", ".log", ".pfx", ".crt"));

        /** 禁止出现的路径片段 */
        private List<String> blockedPathParts = new ArrayList<>(List.of(
                ".git/", "node_modules/", ".toolchain/", "deploy/data/", ".ssh/"));

        /** 允许访问的根目录。留空 = 只允许程序的工作目录 */
        private List<String> allowedRoots = new ArrayList<>();

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public List<String> getBlockedExtensions() {
            return blockedExtensions;
        }

        public void setBlockedExtensions(List<String> blockedExtensions) {
            this.blockedExtensions = blockedExtensions;
        }

        public List<String> getBlockedPathParts() {
            return blockedPathParts;
        }

        public void setBlockedPathParts(List<String> blockedPathParts) {
            this.blockedPathParts = blockedPathParts;
        }

        public List<String> getAllowedRoots() {
            return allowedRoots;
        }

        public void setAllowedRoots(List<String> allowedRoots) {
            this.allowedRoots = allowedRoots;
        }
    }

    // ==================== getter / setter ====================

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isKillSwitch() {
        return killSwitch;
    }

    public void setKillSwitch(boolean killSwitch) {
        this.killSwitch = killSwitch;
    }

    public Access getAccess() {
        return access;
    }

    public void setAccess(Access access) {
        this.access = access;
    }

    public RateLimit getRateLimit() {
        return rateLimit;
    }

    public void setRateLimit(RateLimit rateLimit) {
        this.rateLimit = rateLimit;
    }

    public Budget getBudget() {
        return budget;
    }

    public void setBudget(Budget budget) {
        this.budget = budget;
    }

    public Outbound getOutbound() {
        return outbound;
    }

    public void setOutbound(Outbound outbound) {
        this.outbound = outbound;
    }

    public ContentGate getContentGate() {
        return contentGate;
    }

    public void setContentGate(ContentGate contentGate) {
        this.contentGate = contentGate;
    }

    public Words getWords() {
        return words;
    }

    public void setWords(Words words) {
        this.words = words;
    }

    public FileAccess getFileAccess() {
        return fileAccess;
    }

    public void setFileAccess(FileAccess fileAccess) {
        this.fileAccess = fileAccess;
    }
}
