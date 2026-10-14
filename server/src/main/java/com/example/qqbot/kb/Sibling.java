package com.example.qqbot.kb;

/**
 * 「同一份资料的兄弟块补全」配置。
 *
 * <h2>解决什么问题</h2>
 * 超长的资料会被切成多块（wiki 一页可能切成 {@code xxx}、{@code xxx-2}、{@code xxx-3}…）。
 * 检索是按**块**做的，所以一个问题可能只命中其中一块 —— 而那一块往往只讲了整份资料的一小节。
 * 旧行为是把这一块单独喂给模型，于是「升级材料」那块被命中、而「是什么」那块没被命中时，
 * 模型手里的资料就是残缺的，它会照着残缺资料回答。
 *
 * <p>开启后：命中某一块时，把**同一份资料（docId 相同）的其它块**追加到注入内容里。
 * 补出来的块一律**追加在排序结果之后**，所以前 k 名的排名读数完全不受影响。
 *
 * <h2>为什么要有预算</h2>
 * 一份资料可能被切成十几块（机制页实测约 11KB）。全量补进去会把 prompt 撑爆，
 * 也会稀释真正相关那一块的注意力。所以有两个上限：补几块、补多少字。
 */
public class Sibling {

    /** 总开关。关掉就回到"命中哪块只给哪块"的老行为 */
    private boolean enabled = true;

    /** 一次检索最多补几块（不含本来命中的那些） */
    private int maxBlocks = 6;

    /** 一次检索最多补多少字（按块正文长度累计） */
    private int maxChars = 5000;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getMaxBlocks() {
        return maxBlocks;
    }

    public void setMaxBlocks(int maxBlocks) {
        this.maxBlocks = maxBlocks;
    }

    public int getMaxChars() {
        return maxChars;
    }

    public void setMaxChars(int maxChars) {
        this.maxChars = maxChars;
    }
}
