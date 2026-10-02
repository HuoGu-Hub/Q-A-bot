package com.example.qqbot.config;

import com.example.qqbot.media.KbImages;
import com.example.qqbot.media.MediaPolicy;
import com.example.qqbot.media.TempImages;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 媒体文件的目录与清理策略绑定 —— 对应 application.yml 里的 {@code app.media.*}
 *
 * <p><b>为什么要有这个配置：</b>图片类文件会以两种完全不同的身份存在，
 * 它们的生命周期正好相反，<b>必须物理隔离</b>：
 * <ul>
 *   <li><b>临时图片</b>（{@code tmp-images}）——用户发来的图、生成/转发用的中间产物。
 *       随时可以删，由定时任务按「最后修改时间」自动清理。</li>
 *   <li><b>知识库图片</b>（{@code kb-images}）——长期资产，永久保留。
 *       任何清理逻辑都<b>绝不能</b>碰它。</li>
 * </ul>
 *
 * <p>隔离不能只靠"约定"，所以 {@link com.example.qqbot.media.MediaStorageGuard}
 * 会在启动时校验两者不是同一个目录，配错就直接拒绝启动。
 *
 * <h2>2026-10-02：{@code TempImages} / {@code KbImages} 搬去了 {@code media} 包</h2>
 * 它们是<b>媒体层的领域词汇</b>，只是碰巧从 yml 绑定过来 —— 留在本类的嵌套类里，
 * 会让 {@code media} 包看起来"依赖配置的形状"。
 *
 * <p>搬迁是**纯搬运**：yml 的键一个都没变，绑定关系也没变 ——
 * 本类仍然持有那两个对象、仍然由 Spring 填值。
 *
 * <p>业务侧只读视图见 {@link MediaPolicy} —— 业务包只依赖它，不依赖本类。
 */
@ConfigurationProperties(prefix = "app.media")
public class MediaProperties implements MediaPolicy {

    /**
     * 单条消息最多处理几张图。
     *
     * <p>为什么必须有上限：一条消息带 N 张图时，这 N 张会被**同时**读进内存并转成 Base64，
     * 单张峰值约占文件大小的 2.33 倍（byte[] 1 倍 + Base64 字符串 1.33 倍）。
     * 再叠加上事件线程池的并发（默认 8），一条"刷了 20 张图"的群消息就能把 2G 机器顶到危险区。
     *
     * <p>只取前 N 张，超出的**只记日志、不报错**，silently 丢弃。
     * 0 = 不限制（不建议）。
     */
    private int maxImagesPerMessage = 3;

    private TempImages tempImages = new TempImages();

    private KbImages kbImages = new KbImages();

    // ==================== getter / setter ====================

    @Override
    public int getMaxImagesPerMessage() {
        return maxImagesPerMessage;
    }

    public void setMaxImagesPerMessage(int maxImagesPerMessage) {
        this.maxImagesPerMessage = maxImagesPerMessage;
    }

    @Override
    public TempImages getTempImages() {
        return tempImages;
    }

    public void setTempImages(TempImages tempImages) {
        this.tempImages = tempImages;
    }

    @Override
    public KbImages getKbImages() {
        return kbImages;
    }

    public void setKbImages(KbImages kbImages) {
        this.kbImages = kbImages;
    }
}
