package com.example.qqbot.command;

import java.util.List;

/**
 * 一个板块 —— 群友看到的是 {@code /板块 /动作}。
 *
 * <h2>为什么板块是主体</h2>
 * 单独的"动作"没有现实意义：{@code /create} 单独发出来，谁知道创建什么。
 * 所以动作只作为一个完整命令的后半截，{@code /公告 /create} 才是一件事。
 *
 * <h2>板块持有三样东西</h2>
 * <ol>
 *   <li><b>参数表</b>：这个板块的数据长什么样（{@link #params}）</li>
 *   <li><b>投递方式</b>：收下之后立刻展示，还是到点再发（{@link #delivery}）</li>
 *   <li><b>三把锁</b>：谁能用、能不能私聊、能看哪些群（{@link #access} 与 {@link #policy}）</li>
 * </ol>
 *
 * <p>四个动作（增删查、帮助）由框架统一实现，板块不写代码。
 *
 * @param path    命令名，也是群里的调用名，不含斜杠。中英皆可
 * @param title   历史列，**永远等于 {@link #path}**；不再允许"显示名"和调用名分开
 * @param params  这个板块的数据形状
 * @param delivery {@link #DIRECT} 立即展示 / {@link #SCHEDULED} 到点发
 */
public record Board(
        long id,
        String path,
        String title,
        List<ParamSpec> params,
        String delivery,
        String access,
        DataPolicy policy,
        boolean enabled,
        int sortOrder,
        boolean builtin,
        String listHeader,
        String listItem,
        String listEmpty,
        int listLimit,
        String listReply,
        String helpReply,
        String description) {

    /** 立即展示：收下就出现在结果里 */
    public static final String DIRECT = "direct";
    /** {@link BoardRecord} 里引用的同名字面量，避免两处各写一遍 */
    public static final String DELIVERY_SCHEDULED = "scheduled";
    /** 到点发：收下之后等时间到了再发出去 */
    public static final String SCHEDULED = "scheduled";

    /** 动作全部由框架实现：增、查、删、看命令、看用法 */
    public static final String ACT_CREATE = "create";
    public static final String ACT_QUERY = "query";
    public static final String ACT_DELETE = "delete";
    public static final String ACT_LIST = "list";
    public static final String ACT_HELP = "help";

    /** 不带动作时的默认动作 —— 群友发 {@code /公告} 就是在问"有什么" */
    public static final String DEFAULT_ACTION = ACT_QUERY;

    public static final List<String> ACTIONS =
            List.of(ACT_CREATE, ACT_QUERY, ACT_DELETE, ACT_LIST, ACT_HELP);

    public Board {
        path = path == null ? "" : path.trim();
        // 命令名与调用名强绑定：title 列保留只为兼容老库，任何地方都不再能把它写成另一个名字
        title = path;
        params = params == null ? List.of() : List.copyOf(params);
        policy = policy == null ? DataPolicy.DEFAULT : policy;
        delivery = SCHEDULED.equals(delivery) ? SCHEDULED : DIRECT;
        listHeader = listHeader == null ? "" : listHeader;
        listItem = listItem == null ? "" : listItem;
        listEmpty = listEmpty == null ? "" : listEmpty;
        // 0 = 全部；上限只防一次拉太多，不改变"全部"的语义
        listLimit = listLimit <= 0 ? 0 : Math.min(listLimit, 200);
        listReply = listReply == null ? "" : listReply;
        helpReply = helpReply == null ? "" : helpReply;
        description = description == null ? "" : description;
    }

    /** 兼容旧构造：没有 list/help 回复 */
    public Board(long id, String path, String title, List<ParamSpec> params, String delivery,
                 String access, DataPolicy policy, boolean enabled, int sortOrder, boolean builtin,
                 String listHeader, String listItem, String listEmpty, int listLimit) {
        this(id, path, title, params, delivery, access, policy, enabled, sortOrder, builtin,
                listHeader, listItem, listEmpty, listLimit, "", "", "");
    }

    /** 兼容旧构造：没有列表格式配置时沿用框架默认排版 */
    public Board(long id, String path, String title, List<ParamSpec> params, String delivery,
                 String access, DataPolicy policy, boolean enabled, int sortOrder, boolean builtin) {
        this(id, path, title, params, delivery, access, policy, enabled, sortOrder, builtin,
                "", "", "", 20, "", "", "");
    }

    /** 群友看到的名字 = 调用名 */
    public String displayTitle() {
        return path;
    }

    public boolean isScheduled() {
        return SCHEDULED.equals(delivery);
    }

    /** 这个动作认不认得出来；认不出来就按默认动作 */
    public static String normalizeAction(String raw) {
        if (raw == null || raw.isBlank()) {
            return DEFAULT_ACTION;
        }
        String s = raw.trim().toLowerCase();
        return ACTIONS.contains(s) ? s : DEFAULT_ACTION;
    }
}
