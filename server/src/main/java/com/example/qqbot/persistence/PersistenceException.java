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
        super(compose(message, cause), cause);
    }

    public PersistenceException(String message) {
        super(message);
    }

    /**
     * 把真正的原因拼进 message。
     *
     * <p>为什么需要：持久层的 message 说的是「做了什么」
     * （{@code 更新失败：UPDATE kb_proposal ...}），真正有用的原因
     * （{@code [SQLITE_BUSY] database is locked}）在 cause 里。
     * 业务侧有几处把 {@code e.getMessage()} **直接回给前端**，
     * 不拼的话那些提示会退化成一句 SQL 文本，谁也看不出出了什么事。
     */
    private static String compose(String message, Throwable cause) {
        String detail = cause == null ? null : cause.getMessage();
        return detail == null || detail.isBlank() ? message : message + " —— " + detail;
    }
}
