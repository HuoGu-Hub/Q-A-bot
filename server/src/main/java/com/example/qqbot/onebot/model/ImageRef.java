package com.example.qqbot.onebot.model;

/**
 * 消息里一张图片的「引用信息」。
 *
 * <p>为什么要单独抽一个类型：从 OneBot 图片段里能拿到的信息不止 URL，
 * 还有 {@code file} 字段 —— 而那个字段就是**图片内容的 MD5**
 * （真实样本：{@code "file":"65A82BE1AE2810AEB287D78C2260C823.jpg"}）。
 *
 * <p>这一点是整个图片缓存的地基：
 * <ul>
 *   <li>{@code url} 是 rkey 签名的链接，**会过期**
 *       （实测过期后返回 {@code download url has expired}），不能当身份用；</li>
 *   <li>{@code key}（MD5）在**下载之前**就能拿到，且同一张图永远相同 ——
 *       所以「同一张图要不要重新下载」可以在发出网络请求之前就判断出来。</li>
 * </ul>
 *
 * @param key  图片内容的 MD5（小写 32 位）；拿不到时为 {@code null}，此时退化为不缓存
 * @param url  下载地址（可能是会过期的 rkey 链接）
 * @param size 图片字节数（来自 {@code file_size}），拿不到时为 0
 */
public record ImageRef(String key, String url, long size) {

    /** 有没有可用的缓存键 */
    public boolean hasKey() {
        return key != null && !key.isBlank();
    }

    /** 没有 MD5 的场景（别的客户端/格式），只能下载完再按内容算 */
    public static ImageRef ofUrl(String url) {
        return new ImageRef(null, url, 0);
    }
}
