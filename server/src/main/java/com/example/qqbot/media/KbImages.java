package com.example.qqbot.media;

/**
 * KbImages。
 *
 * <h2>为什么不在 {@code config} 包里（2026-10-02 挪过来）</h2>
 * 它是<b>媒体层的领域词汇</b>（临时图片与知识库图片的目录、清理策略），只是碰巧从
 * {@code app.media.*} 绑定过来。留在 {@code MediaProperties} 的嵌套类里，会让
 * {@code media} 包看起来"依赖配置的形状"。
 * 搬迁是**纯搬运**：零语义变化，yml 的键一个都没动。
 */
public class KbImages {

    /** 知识库图片目录。永久保留，清理逻辑的白名单之外 */
    private String dir = "./data/kb-images";

    public String getDir() {
        return dir;
    }

    public void setDir(String dir) {
        this.dir = dir;
    }
}
