package com.example.qqbot.voice;

/**
 * 语音发送的策略 —— **业务侧声明的只读视图**，由 {@code config.VoiceProperties} 实现。
 *
 * <h2>为什么是接口而不是配置类</h2>
 * 沿用 {@code media.MediaPolicy} / {@code command.CommandPolicy} 的做法：
 * 业务包不 import {@code config}，依赖方向只能是 {@code config → voice}。
 * 写成接口还有一个原因：配置是热生效的，各 Bean 长期持引用、调用时实时读；
 * 换成 record 快照就再也看不到后台的修改，而且不报错。
 */
public interface VoicePolicy {

    /** 总开关。关掉后 {@code VoiceSender} 直接拒绝，不会碰协议端 */
    boolean isEnabled();

    /**
     * 加工后音频的大小上限（KB）。0 = 不限制（不建议）。
     *
     * <p>为什么要管：音频要 base64 塞进消息体，体积膨胀约 1.33 倍；
     * 一条 3 分钟的语音在内存里就是两三份 MB 级的字节数组。
     */
    int getMaxKb();

    /**
     * 单条语音的时长上限（秒）。0 = 不限制。
     *
     * <p>协议端实测并不卡时长（179 秒照样收），这里卡的是**体验**：
     * 语音条不是播放器，超过几分钟对方只能从头听到尾。
     */
    int getMaxSeconds();

    /** ffmpeg 可执行文件路径。默认就是 {@code ffmpeg}，依赖 PATH */
    String getFfmpeg();

    /** ffprobe 可执行文件路径，用来量加工后的时长 */
    String getFfprobe();

    /** 单次转码的超时（秒）。超时直接杀掉进程，不让一个坏文件卡住发送线程 */
    int getTimeoutSeconds();
}
