package com.example.qqbot;

/**
 * 测试里用的账号类占位 ID。
 *
 * <p>为什么不把真实 QQ 号 / 群号写进测试：仓库是公开的，真实账号一旦提交就会
 * 永久留在 git 历史里。这里只放<strong>明显是假值</strong>的占位号；需要一个真实号
 * 来联调时，请走环境变量（如 {@code BOT_QQ}）或本地未提交的配置，不要粘贴回代码。
 */
public final class TestIds {

    /** 用户 QQ（占位假值） */
    public static final long USER = 100000001L;

    /** 群号（占位假值） */
    public static final long GROUP = 100000002L;

    /** 机器人自身 QQ（占位假值） */
    public static final long BOT = 100000003L;

    private TestIds() {
    }
}
