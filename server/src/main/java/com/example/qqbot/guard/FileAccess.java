package com.example.qqbot.guard;

import java.util.ArrayList;
import java.util.List;

/**
 * 文件访问守卫 —— 哪些扩展名/路径片段不许出现、哪些根目录允许访问。
 * *
 * <h2>为什么不在 {@code config} 包里（2026-10-02 挪过来）</h2>
 * 它是<b>安全中间层的领域词汇</b>，只是碰巧从 {@code app.guard.*} 绑定过来。
 * 留在 {@code GuardProperties} 的嵌套类里，会让 {@code guard} 包看起来"依赖配置的形状" ——
 * 而 13 个消费者早就通过 {@code GuardConfig} 的 bean 直接注入它了，根本没有那回事。
 * 搬迁是**纯搬运**：零语义变化，只是把"这个形状归谁"摆正。
 */
public class FileAccess {

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
