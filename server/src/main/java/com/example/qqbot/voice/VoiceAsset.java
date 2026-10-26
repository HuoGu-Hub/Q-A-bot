package com.example.qqbot.voice;

/**
 * 素材库里的一条语音。
 *
 * <p>文件本身在磁盘上，这里只存元数据 —— 二进制进库会让备份、试听、清理都变难。
 * {@code file} 是磁盘上的文件名，用内容哈希命名，天然去重。
 */
public record VoiceAsset(long id, String title, String aliases, String tags,
                         String file, String mime, long sizeBytes, long durationMs,
                         boolean enabled, String createdAt) {

    /** 供前端显示的一句话描述 */
    public String describe() {
        String seconds = String.format(java.util.Locale.ROOT, "%.1f", durationMs / 1000.0);
        return title + "（" + seconds + " 秒）";
    }
}
