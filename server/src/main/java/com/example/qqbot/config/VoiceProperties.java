package com.example.qqbot.config;

import com.example.qqbot.voice.VoicePolicy;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 语音发送配置 —— 对应 {@code app.voice.*}。
 *
 * <p>业务侧只认 {@link VoicePolicy}，本类只是它在配置层的一个实现，
 * 这样 {@code voice} 包不需要 import {@code config}。
 *
 * <h2>为什么超时和路径也做成可配</h2>
 * ffmpeg 不一定在 PATH 里（Windows 上经常是某个目录下的 exe，服务器上则是
 * {@code /usr/bin/ffmpeg}）。路径写死会让部署时多一次改代码，
 * 而这恰恰是最容易出问题的一步。
 */
@ConfigurationProperties(prefix = "app.voice")
public class VoiceProperties implements VoicePolicy {

    /** 总开关。关掉后 {@code VoiceSender} 直接拒绝，不碰 ffmpeg 也不碰协议端 */
    private boolean enabled = true;

    /**
     * 加工后音频的大小上限（KB）。
     *
     * <p>base64 会让体积膨胀约 1.33 倍，一条 3 分钟的语音在内存里是几 MB；
     * 默认 1MB 对应大约 4 分钟的 32kbps 音频，够一首歌的副歌循环。
     */
    private int maxKb = 1024;

    /**
     * 单条语音的时长上限（秒）。0 = 不限制。
     *
     * <p>协议端实测不卡时长（179 秒照样收），这里卡的是体验：
     * 语音条没法拖进度，太长的东西没人听完。默认 5 分钟。
     */
    private int maxSeconds = 300;

    /** ffmpeg 可执行文件。只写文件名时从 PATH 找 */
    private String ffmpeg = "ffmpeg";

    /** ffprobe 可执行文件，用来量加工后的时长 */
    private String ffprobe = "ffprobe";

    /** 单次转码超时（秒） */
    private int timeoutSeconds = 60;

    /** 素材库目录：上传的音频与 TTS 缓存都落在这里，必须可写 */
    private String dir = "./data/voices";

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    @Override
    public int getMaxKb() {
        return maxKb;
    }

    public void setMaxKb(int maxKb) {
        this.maxKb = maxKb;
    }

    @Override
    public int getMaxSeconds() {
        return maxSeconds;
    }

    public void setMaxSeconds(int maxSeconds) {
        this.maxSeconds = maxSeconds;
    }

    @Override
    public String getFfmpeg() {
        return ffmpeg;
    }

    public void setFfmpeg(String ffmpeg) {
        this.ffmpeg = ffmpeg;
    }

    @Override
    public String getFfprobe() {
        return ffprobe;
    }

    public void setFfprobe(String ffprobe) {
        this.ffprobe = ffprobe;
    }

    @Override
    public int getTimeoutSeconds() {
        return timeoutSeconds;
    }

    public void setTimeoutSeconds(int timeoutSeconds) {
        this.timeoutSeconds = timeoutSeconds;
    }

    @Override
    public String getVoicesDir() {
        return dir;
    }

    public void setDir(String dir) {
        this.dir = dir;
    }
}
