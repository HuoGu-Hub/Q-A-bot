package com.example.qqbot.onebot.model;

/**
 * 一张要发给群友的图片。
 *
 * <h2>为什么只有两种形态</h2>
 * 协议端只认这两种来源，多一种形态就多一处没人走的死代码：
 * <ul>
 *   <li><b>公网直链</b>（{@link #ofUrl}）—— 图本来就在公网上，让协议端自己去取。
 *       我们这边零下载、零内存；</li>
 *   <li><b>本地字节</b>（{@link #ofBytes}）—— 图在我们磁盘上（知识库配图、合成的地图截图）。
 *       协议端跑在容器里，<b>看不到宿主机的路径</b>，
 *       所以只能把字节内联进消息，而不是给它一个 file:// 路径。</li>
 * </ul>
 *
 * <p>⚠️ 记录里的 {@code byte[]} 按引用比较，别拿整个记录做相等断言 —— 比字段。
 */
public record OutboundImage(String url, byte[] bytes, String mimeType) {

    private static final String DEFAULT_MIME = "image/jpeg";

    /** 两种来源必须**恰好有一种**：都给或都不给都是调用方的 bug，宁可当场炸 */
    public OutboundImage {
        boolean hasUrl = url != null && !url.isBlank();
        boolean hasBytes = bytes != null && bytes.length > 0;
        if (hasUrl == hasBytes) {
            throw new IllegalArgumentException("出站图片必须且只能有一种来源：公网直链或本地字节");
        }
        if (hasUrl) {
            url = url.trim();
        }
    }

    public static OutboundImage ofUrl(String url) {
        return new OutboundImage(url, null, null);
    }

    public static OutboundImage ofBytes(byte[] bytes, String mimeType) {
        String mime = (mimeType == null || mimeType.isBlank()) ? DEFAULT_MIME : mimeType;
        return new OutboundImage(null, bytes, mime);
    }

    public boolean isUrl() {
        return url != null;
    }

    /** 本地字节的 MIME；直链形态返回 null，类型由对方的响应头决定 */
    public String bytesMime() {
        return bytes == null ? null : (mimeType == null || mimeType.isBlank() ? DEFAULT_MIME : mimeType);
    }

    public int byteSize() {
        return bytes == null ? 0 : bytes.length;
    }

    /**
     * 只有本地字节能提前判大小 —— 直链多大要等协议端去取才知道，
     * 所以大小闸门对直链是"不管"，不是"通过"。
     */
    public boolean exceedsBytes(int maxBytes) {
        return bytes != null && maxBytes > 0 && bytes.length > maxBytes;
    }

    /** 日志里只留来源与大小，绝不打印字节内容 */
    @Override
    public String toString() {
        return isUrl()
                ? "OutboundImage[url]"
                : "OutboundImage[bytes=" + byteSize() + "B, " + bytesMime() + "]";
    }
}
