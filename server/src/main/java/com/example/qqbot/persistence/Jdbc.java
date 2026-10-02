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
