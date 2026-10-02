package com.example.qqbot.guard;

/**
 * 安全中间层的两个**根级开关** —— 总开关与应急开关。
 *
 * <h2>为什么它是接口，而 7 个分组是「挪过来的类」</h2>
 * 这两条判据不一样，值得写清楚：
 * <ul>
 *   <li>{@code Access} / {@code RateLimit} / … 是<b>完整的领域词汇</b>（5~9 个字段、
 *       有内在语义），它们只是碰巧从 yml 绑定 —— 所以**整个类挪进领域包**，
 *       零语义变化、零新抽象。</li>
 *   <li>{@code enabled} / {@code killSwitch} 是<b>根级的两条开关</b>，不是能独立成类的
 *       领域对象；而它们的 yml 键（{@code app.guard.enabled}）就在根上，
 *       挪成嵌套对象会改键名、破坏现网配置。所以只**声明一个两方法的只读接口**。</li>
 * </ul>
 * 判据一句话：**能整体搬走就搬走，搬不动（键在根上）就声明只读视图。**
 *
 * <p>⚠️ 接口而不是 record：配置是热生效的（{@code SettingsService} 就地改字段、
 * 各 Bean 长期持引用实时读），写成快照就再也看不到后台的修改，而且不报错。
 */
public interface GuardSwitch {

    /** 总开关。关掉后所有限制失效（调试用） */
    boolean isEnabled();

    /**
     * 应急开关。打开后机器人**完全不回复**（连兜底词都不回）。
     *
     * <p>出事的时候你需要一条命令让它立刻闭嘴。
     */
    boolean isKillSwitch();
}
