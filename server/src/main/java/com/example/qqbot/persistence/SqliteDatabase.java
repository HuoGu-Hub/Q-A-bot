package com.example.qqbot.persistence;

import com.example.qqbot.config.QaProperties;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * SQLite 连接的**唯一持有者** —— 连接所有权从 {@code qa.QaStore} 移交到这里。
 *
 * <h2>为什么要移交</h2>
 * 原来 {@code QaStore} 既管问答记录、又持有全项目唯一的连接，于是 8 个包里的类
 * 得写 {@code @DependsOn("qaStore")} 来保证"连上库"先发生 —— 那是**字符串形式的
 * 运行期耦合**，编译期看不出来，改个类名就静默失效。
 *
 * <p>移交之后：谁需要数据库就在构造器里要 {@link SqliteDatabase}，
 * Spring 保证依赖先初始化完（含 {@code @PostConstruct}），
 * **8 处 {@code @DependsOn("qaStore")} 可以直接删掉**，不再需要字符串约定。
 *
 * <p><b>为什么标 {@code @Primary}</b>：{@code QaStore} 为了兼容测试还临时实现了
 * {@link SqliteConnectionProvider}（纯转发），于是按类型注入会有两个候选。
 * 标了 {@code @Primary} 之后生产环境一律拿到本类。
 * 等最后一个 Store 迁到 {@code Jdbc} 之后，那个转发实现会一并删掉。
 *
 * <p>⚠️ 配置仍借用 {@code app.qa.db} / {@code app.qa.enabled}（那是历史上第一个
 * 需要库的功能）。键名确实该叫 {@code app.persistence.*}，但改名会动到现网配置，
 * 单独一步做。
 */
@Component
@Primary
public class SqliteDatabase implements SqliteConnectionProvider, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(SqliteDatabase.class);

    private final QaProperties props;
    private final Object lock = new Object();

    private Connection conn;
    private volatile boolean available;

    public SqliteDatabase(QaProperties props) {
        this.props = props;
    }

    @PostConstruct
    public void init() {
        if (!props.isEnabled()) {
            log.info("[DB] 数据库已关闭（app.qa.enabled=false）");
            return;
        }
        try {
            Path file = Paths.get(props.getDb()).toAbsolutePath().normalize();
            Files.createDirectories(file.getParent());
            conn = DriverManager.getConnection("jdbc:sqlite:" + file);
            try (Statement st = conn.createStatement()) {
                st.execute("PRAGMA journal_mode=WAL");
                st.execute("PRAGMA busy_timeout=5000");
                st.execute("PRAGMA synchronous=NORMAL");
            }
            available = true;
            log.info("[DB] SQLite 就绪：{}", file);
        } catch (Exception e) {
            log.warn("[DB] 打开数据库失败，本次运行所有依赖库的功能降级：{}", e.getMessage());
            available = false;
        }
    }

    /**
     * 共用连接。**调用方不要自己加锁** —— 统一走 {@link Jdbc}，
     * 那里有一把全局可重入锁（JDBC 连接不是线程安全的）。
     */
    @Override
    public Connection connection() {
        return conn;
    }

    @Override
    public boolean isAvailable() {
        return available;
    }

    @PreDestroy
    @Override
    public void close() {
        synchronized (lock) {
            try {
                if (conn != null && !conn.isClosed()) {
                    conn.close();
                }
            } catch (SQLException ignored) {
                // 关不掉就算了
            }
        }
    }
}
