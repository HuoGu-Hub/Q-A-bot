package com.example.qqbot.persistence;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * SQLite 访问的**唯一入口** —— 全项目只有 {@code persistence} 包能碰 {@code java.sql}。
 *
 * <h2>为什么要有它（不只是为了分层好看）</h2>
 * <b>1. 修掉一个真实的并发缺陷。</b>
 * 全项目共用一个 SQLite 连接，而 JDBC 连接**不是线程安全的**。
 * {@link SqliteConnectionProvider} 的注释写着"调用方自己负责同步"，
 * 但实测 14 个手写 SQL 的类里：6 个用 {@code synchronized (this)}、1 个用同步方法、
 * **另外 8 个完全没有同步**（{@code KbMapStore} / {@code KbWikiPageStore} /
 * {@code AnswerAggregator} / {@code QaAnalytics} / {@code QaStore} / {@code SiteTextService} /
 * {@code KbProposalService} / {@code PlazaAdminController}）。
 * 靠"每个人记得加锁"是行不通的 —— 这里**统一加锁**。
 *
 * <p>本类是 Spring 单例，所以 {@code monitor} 是**全局唯一**的；
 * 若每个 Repository 各自 new 一个，锁就失效了。
 * {@code synchronized} 可重入，因此 {@link #inLock} 里调用本类的其它方法不会死锁。
 *
 * <p><b>2. 收掉资源生命周期。</b>419 处行内引用里大量是
 * {@code try (PreparedStatement ps = ...) { try (ResultSet rs = ...) }} 的样板，
 * 以及不一致的 {@code SQLException} 处理（有的返回 0、有的返回空、有的吞掉）。
 *
 * <p><b>3. 让"SQL 搬进 Repository"这件事有落脚点。</b>
 * 本类只管**怎么执行**，不管**执行什么** —— SQL 语句属于各个 Repository。
 */
@Component
public class Jdbc {

    private static final Logger log = LoggerFactory.getLogger(Jdbc.class);

    private final SqliteConnectionProvider db;

    /** 全局互斥。共用连接 + SQLite ⇒ 本来就得串行化，这里显式做掉 */
    private final Object monitor = new Object();

    /**
     * 事务嵌套深度（**只在 monitor 内访问**）。
     *
     * <p>为什么需要它：{@link #batch} 原先自己 {@code setAutoCommit(false)}…{@code commit()}…
     * {@code setAutoCommit(true)}。如果它被调在一个已经开着的事务里（{@code transaction} 的块内），
     * 那一下 {@code setAutoCommit(true)} 会把**外层事务的工作提前提交**，
     * 并让外层随后的 {@code commit()} 报 {@code database in auto-commit mode}。
     * 实测就是这样炸的（见 {@code JdbcTest.reentrantInsideTransaction}）。
     *
     * <p>现在只有**最外层**才真正开关自动提交与提交/回滚；内层只干活。
     */
    private int txDepth = 0;

    public Jdbc(SqliteConnectionProvider db) {
        this.db = db;
    }

    public boolean isAvailable() {
        return db.isAvailable();
    }

    /** DDL 或无参语句 */
    public void execute(String sql) {
        synchronized (monitor) {
            try (Statement st = conn().createStatement()) {
                st.execute(sql);
            } catch (SQLException e) {
                throw new PersistenceException("执行失败：" + sql, e);
            }
        }
    }

    /** 增 / 删 / 改，返回影响行数 */
    public int update(String sql, Object... args) {
        synchronized (monitor) {
            try (PreparedStatement ps = conn().prepareStatement(sql)) {
                bind(ps, args);
                return ps.executeUpdate();
            } catch (SQLException e) {
                throw new PersistenceException("更新失败：" + sql, e);
            }
        }
    }

    /**
     * 插入并返回自增主键；取不到返回 -1。
     *
     * <p>和 {@link #update} 分开是因为 {@code RETURN_GENERATED_KEYS} 得在
     * prepareStatement 时就声明，事后没法补。
     */
    public long insert(String sql, Object... args) {
        synchronized (monitor) {
            try (PreparedStatement ps = conn().prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
                bind(ps, args);
                ps.executeUpdate();
                try (ResultSet rs = ps.getGeneratedKeys()) {
                    return rs.next() ? rs.getLong(1) : -1L;
                }
            } catch (SQLException e) {
                throw new PersistenceException("插入失败：" + sql, e);
            }
        }
    }

    /** 查多行 */
    public <T> List<T> query(String sql, RowMapper<T> mapper, Object... args) {
        synchronized (monitor) {
            try (PreparedStatement ps = conn().prepareStatement(sql)) {
                bind(ps, args);
                try (ResultSet rs = ps.executeQuery()) {
                    List<T> out = new ArrayList<>();
                    while (rs.next()) {
                        out.add(mapper.map(new ResultRow(rs)));
                    }
                    return out;
                }
            } catch (SQLException e) {
                throw new PersistenceException("查询失败：" + sql, e);
            }
        }
    }

    /** 查一行；没有则返回 {@code null} */
    public <T> T queryOne(String sql, RowMapper<T> mapper, Object... args) {
        List<T> all = query(sql, mapper, args);
        return all.isEmpty() ? null : all.get(0);
    }

    /** 查一个整数（COUNT 之类）。没有行时返回 0 */
    public long count(String sql, Object... args) {
        synchronized (monitor) {
            try (PreparedStatement ps = conn().prepareStatement(sql)) {
                bind(ps, args);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getLong(1) : 0L;
                }
            } catch (SQLException e) {
                throw new PersistenceException("计数失败：" + sql, e);
            }
        }
    }

    /**
     * 批量执行同一条 SQL（**一个事务**）。
     *
     * <p>为什么要有它：批量 upsert 必须整体成功或整体回滚 ——
     * 一半写进去的映射表比没写更糟（谁也说不清哪些生效了）。
     *
     * @param rows 每行的参数，顺序与 SQL 里的 {@code ?} 对应
     * @return 提交的行数；任何一行失败则整体回滚并抛 {@link PersistenceException}
     */
    public int batch(String sql, List<Object[]> rows) {
        synchronized (monitor) {
            Connection c = conn();
            boolean outermost = txDepth == 0;
            try {
                if (outermost) {
                    c.setAutoCommit(false);
                }
                int n = 0;
                try (PreparedStatement ps = c.prepareStatement(sql)) {
                    for (Object[] args : rows) {
                        bind(ps, args);
                        ps.addBatch();
                        n++;
                    }
                    ps.executeBatch();
                }
                if (outermost) {
                    c.commit();
                }
                return n;
            } catch (SQLException e) {
                if (outermost) {
                    try {
                        c.rollback();
                    } catch (SQLException ignored) {
                        // 回滚也失败就只能记着原异常
                    }
                }
                throw new PersistenceException("批量执行失败：" + sql, e);
            } finally {
                if (outermost) {
                    try {
                        c.setAutoCommit(true);
                    } catch (SQLException ignored) {
                        // 恢复自动提交失败不影响已提交的结果
                    }
                }
            }
        }
    }

    /**
     * **显式事务边界** —— 整段成功才提交，抛异常则回滚。
     *
     * <h2>为什么 batch() 不够</h2>
     * {@link #batch} 只能表达"同一批语句一起写"。而 {@code KbTermStore.setStatusBatch}
     * 这类操作是**读与写交替**：先 {@code SELECT status} 看旧值、再决定这条要不要
     * {@code UPDATE}，循环若干条。那不是一批语句，是一段逻辑，必须整体原子。
     *
     * <p>块内照常调用 {@link #update}/{@link #query}/{@link #batch}（同一把可重入锁，
     * 不会死锁，也不会各自提交 —— 它们用的是同一个连接与同一个事务）。
     *
     * @throws PersistenceException 提交失败时；业务异常原样上抛（回滚后）
     */
    public <T> T transaction(Callable<T> body) {
        synchronized (monitor) {
            Connection c = conn();
            boolean outermost = txDepth == 0;
            if (outermost) {
                try {
                    c.setAutoCommit(false);
                } catch (SQLException e) {
                    throw new PersistenceException("开启事务失败", e);
                }
            }
            txDepth++;
            try {
                T result = body.call();
                if (outermost) {
                    c.commit();
                }
                return result;
            } catch (Exception e) {
                if (outermost) {
                    try {
                        c.rollback();
                    } catch (SQLException ignored) {
                        // 回滚也失败就只能记着原异常
                    }
                }
                if (e instanceof RuntimeException re) {
                    throw re;
                }
                throw new PersistenceException("事务执行失败", e);
            } finally {
                txDepth--;
                if (outermost) {
                    try {
                        c.setAutoCommit(true);
                    } catch (SQLException ignored) {
                        // 恢复自动提交失败不影响已提交/已回滚的结果
                    }
                }
            }
        }
    }

    /**
     * 需要"读-改-写"原子性时把整段包起来。
     *
     * <p>块内再调用 {@link #update}/{@link #query} 不会死锁（同一把可重入锁）。
     */
    public <T> T inLock(Callable<T> body) {
        synchronized (monitor) {
            try {
                return body.call();
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new PersistenceException("事务块执行失败", e);
            }
        }
    }

    public void inLock(Runnable body) {
        inLock(() -> {
            body.run();
            return null;
        });
    }

    private Connection conn() {
        return db.connection();
    }

    private static void bind(PreparedStatement ps, Object[] args) throws SQLException {
        if (args == null) {
            return;
        }
        for (int i = 0; i < args.length; i++) {
            Object a = args[i];
            int idx = i + 1;
            if (a == null) {
                ps.setNull(idx, java.sql.Types.NULL);
            } else if (a instanceof String s) {
                ps.setString(idx, s);
            } else if (a instanceof Integer n) {
                ps.setInt(idx, n);
            } else if (a instanceof Long n) {
                ps.setLong(idx, n);
            } else if (a instanceof Boolean b) {
                ps.setInt(idx, b ? 1 : 0);
            } else if (a instanceof Double d) {
                ps.setDouble(idx, d);
            } else if (a instanceof Float f) {
                ps.setDouble(idx, f);
            } else if (a instanceof byte[] bytes) {
                ps.setBytes(idx, bytes);
            } else {
                ps.setObject(idx, a);
            }
        }
    }

    /** 一行数据 —— 业务代码只用它，见不到 {@code ResultSet} */
    public interface Row {

        String str(String column);

        int intOf(String column);

        long longOf(String column);

        double dbl(String column);

        boolean bool(String column);

        boolean isNull(String column);
    }

    @FunctionalInterface
    public interface RowMapper<T> {
        T map(Row row);
    }

    /** {@link Row} 的实现 —— 薄薄一层，只做取值 */
    private record ResultRow(ResultSet rs) implements Row {

        @Override
        public String str(String column) {
            try {
                return rs.getString(column);
            } catch (SQLException e) {
                throw new PersistenceException("读取列失败：" + column, e);
            }
        }

        @Override
        public int intOf(String column) {
            try {
                return rs.getInt(column);
            } catch (SQLException e) {
                throw new PersistenceException("读取列失败：" + column, e);
            }
        }

        @Override
        public long longOf(String column) {
            try {
                return rs.getLong(column);
            } catch (SQLException e) {
                throw new PersistenceException("读取列失败：" + column, e);
            }
        }

        @Override
        public double dbl(String column) {
            try {
                return rs.getDouble(column);
            } catch (SQLException e) {
                throw new PersistenceException("读取列失败：" + column, e);
            }
        }

        @Override
        public boolean bool(String column) {
            try {
                return rs.getInt(column) != 0;
            } catch (SQLException e) {
                throw new PersistenceException("读取列失败：" + column, e);
            }
        }

        @Override
        public boolean isNull(String column) {
            try {
                rs.getObject(column);
                return rs.wasNull();
            } catch (SQLException e) {
                throw new PersistenceException("读取列失败：" + column, e);
            }
        }
    }
}
