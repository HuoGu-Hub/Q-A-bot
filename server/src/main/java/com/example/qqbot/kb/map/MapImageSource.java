package com.example.qqbot.kb.map;

import java.io.IOException;

/**
 * 地图图片的来源 —— **唯一**知道"底图从哪来"的地方。
 *
 * <h2>为什么抽成接口</h2>
 * 缓存逻辑（落盘、命中、原子写）是可以离线测的，而网络请求不行。
 * 把"取字节"这一步抽出来，测试就能塞一个假来源，
 * 于是"缓存命中不再下载"这种断言才测得动 —— 否则只能连真 wiki 测，那是集成测试不是单元测试。
 */
public interface MapImageSource {

    /**
     * 取一张图的字节。
     *
     * @param fileName wiki 上的文件名（如 {@code 0_0.jpg}、{@code Ancient Spire.png}）
     * @param width    目标宽度；{@code <= 0} 表示原图
     * @throws IOException 网络失败、超限、上游非 200
     */
    byte[] fetch(String fileName, int width) throws IOException;

    /** 人话描述当前来源，供管理端展示与排障 */
    String describe();

    /**
     * 上游明确回答"这个文件不存在"。
     *
     * <p>单独一个类型，是因为它和网络抖动**必须区别对待**：
     * 404 是永久性的，重试只是白白占用被限流的出站额度、把日志刷满；
     * 而超时、429 重试是有意义的。上层拿到它会记住"这个文件没有"，不再重复去问。
     */
    class NotFound extends IOException {
        public NotFound(String message) {
            super(message);
        }
    }
}
