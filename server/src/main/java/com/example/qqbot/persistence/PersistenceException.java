package com.example.qqbot.persistence;

/**
 * 持久化层统一往外抛的异常 —— 业务代码**不需要**认识 {@code SQLException}。
 *
 * <p>原先每个 Store 各自 {@code catch (SQLException e) { log.warn(...) }}，
 * 处理方式还不一致（有的返回 0、有的返回空列表、有的吞掉）。
 * 现在 SQL 出错一律变成这个运行时异常，由调用方决定降级还是上抛。
 */
public class PersistenceException extends RuntimeException {

    public PersistenceException(String message, Throwable cause) {
        super(message, cause);
    }

    public PersistenceException(String message) {
        super(message);
    }
}
