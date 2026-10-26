package com.example.qqbot.config;

import com.example.qqbot.voice.TtsPolicy;
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
public class VoiceProperties implements VoicePolicy, TtsPolicy {

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

    /** 文字转语音的配置 */
    private Tts tts = new Tts();

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

    public Tts getTts() {
        return tts;
    }

    public void setTts(Tts tts) {
        this.tts = tts;
    }

    // ==================== TtsPolicy 委托给嵌套对象 ====================

    @Override
    public boolean isTtsEnabled() {
        return tts.isEnabled();
    }

    @Override
    public String getTtsProvider() {
        return tts.getProvider();
    }

    @Override
    public String getEdgeTtsPath() {
        return tts.getEdgeTtsPath();
    }

    @Override
    public String getTtsVoice() {
        return tts.getVoice();
    }

    @Override
    public String getTtsRate() {
        return tts.getRate();
    }

    @Override
    public String getTtsPitch() {
        return tts.getPitch();
    }

    @Override
    public String getTtsVolume() {
        return tts.getVolume();
    }

    @Override
    public int getTtsMaxChars() {
        return tts.getMaxChars();
    }

    @Override
    public int getTtsTimeoutSeconds() {
        return tts.getTimeoutSeconds();
    }

    @Override
    public String getTtsCacheDir() {
        return tts.getCacheDir();
    }

    /** app.voice.tts.* */
    public static class Tts {

        /** 总开关。关掉后所有合成入口直接放弃，退回文字 */
        private boolean enabled = true;

        /** 目前只认 edge-tts */
        private String provider = "edge-tts";

        /** edge-tts 可执行文件。只写命令名时从 PATH 找 */
        private String edgeTtsPath = "edge-tts";

        /** 默认声线 */
        private String voice = "zh-CN-XiaoxiaoNeural";

        /** 语速，edge-tts 的写法 */
        private String rate = "+0%";

        /** 音高 */
        private String pitch = "+0Hz";

        /** 音量 */
        private String volume = "+0%";

        /** 单次合成的字数上限 */
        private int maxChars = 200;

        /** 合成超时（秒） */
        private int timeoutSeconds = 30;

        /** 缓存目录 */
        private String cacheDir = "./data/voices/tts";

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getProvider() {
            return provider;
        }

        public void setProvider(String provider) {
            this.provider = provider;
        }

        public String getEdgeTtsPath() {
            return edgeTtsPath;
        }

        public void setEdgeTtsPath(String edgeTtsPath) {
            this.edgeTtsPath = edgeTtsPath;
        }

        public String getVoice() {
            return voice;
        }

        public void setVoice(String voice) {
            this.voice = voice;
        }

        public String getRate() {
            return rate;
        }

        public void setRate(String rate) {
            this.rate = rate;
        }

        public String getPitch() {
            return pitch;
        }

        public void setPitch(String pitch) {
            this.pitch = pitch;
        }

        public String getVolume() {
            return volume;
        }

        public void setVolume(String volume) {
            this.volume = volume;
        }

        public int getMaxChars() {
            return maxChars;
        }

        public void setMaxChars(int maxChars) {
            this.maxChars = maxChars;
        }

        public int getTimeoutSeconds() {
            return timeoutSeconds;
        }

        public void setTimeoutSeconds(int timeoutSeconds) {
            this.timeoutSeconds = timeoutSeconds;
        }

        public String getCacheDir() {
            return cacheDir;
        }

        public void setCacheDir(String cacheDir) {
            this.cacheDir = cacheDir;
        }
    }
}
