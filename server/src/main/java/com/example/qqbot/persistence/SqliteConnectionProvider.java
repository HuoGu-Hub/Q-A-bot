package com.example.qqbot.persistence;

import java.sql.Connection;

/**
 * 「谁能给我一个 SQLite 连接」—— 把**技术细节**从业务模块里摘出来的那层薄接口。
 *
 * <h2>它解决的具体问题</h2>
 * 全项目共用同一个 SQLite 文件，而且**必须**共用同一个连接：
 * 两个连接开同一个文件会在 WAL 共享内存上冲突（实测 {@code SQLITE_IOERR_SHMOPEN}）。
 * 于是当年的做法是"让所有 Store 都依赖 {@code QaStore}"——问答记录存储顺手当了
 * **全项目的连接提供者**。
 *
 * <p>后果实测：{@code QaStore} 被 **12 个类** import，横跨 8 个业务包
 * （admin / command / kb.block / kb.category / kb.map / kb.proposal / kb.term / plaza / site）。
 * 其中**只有 3 个**是真的在读写问答记录；另外 **9 个**只用 {@code connection()} 与
 * {@code isAvailable()} —— 它们依赖的不是"问答记录"，是"一个连接"。
 *
 * <h2>为什么先做接口而不是直接上 Repository</h2>
 * 一步到位把 12 个类的手写 SQL 全换成 Repository，改动面几千行、且**行为零收益**。
 * 先只换依赖类型：业务模块从"依赖 qa 模块的具体类"变成"依赖一个技术接口"，
 * 编译期依赖就断开了 —— 这一步**不改变任何运行时行为**，却能让
 * "kb 不得依赖 qa" 这条架构规则从目标变成**已生效**。
 *
 * <p>Repository 化是后续步骤；那时这个接口会被 {@code SqliteDatabase} 取代，
 * 业务模块连 {@code Connection} 都不再直接拿。
 *
 * <p><b>实现方</b>：目前是 {@code qa.QaStore}。它是连接的唯一持有者，
 * 也是启动期 {@code @DependsOn} 排序的锚点。
 */
public interface SqliteConnectionProvider {

    /**
     * 共用连接。**调用方自己负责同步**（各 Store 都 {@code synchronized (conn)} 或在
     * 自己的锁里用），因为 SQLite 的 JDBC 连接不是线程安全的。
     */
    Connection connection();

    /** 库不可用时为 false —— 调用方据此把整个功能降级，而不是抛异常 */
    boolean isAvailable();
}
