package com.example.qqbot.voice;

/**
 * 一条语音的加工参数：裁一段、变速、变调、调响度。
 *
 * <h2>为什么要有这一层</h2>
 * 协议端只接受「一段完整音频」，没有"从第 58 秒开始放 30 秒"这种参数。
 * 想让机器人唱副歌、或者把 3 分钟的歌压成 30 秒，就必须在**发出去之前**
 * 用 ffmpeg 裁好 —— 这份参数就是那一步的输入。
 *
 * <h2>取值范围为什么收得这么窄</h2>
 * {@code speed} 0.5~2：ffmpeg 的 atempo 单级只能在这个区间，再宽要串联滤波，
 * 音质会明显劣化，而语音条本来就只有 24kHz。
 * {@code semitones} ±12：再大就只剩"花栗鼠"和"慢放怪兽"，没有可用性。
 *
 * <p>{@link #NONE} 表示原样发送，链路会把整段音频直接交给协议端。
 */
public record VoiceOptions(double startSeconds, double durationSeconds,
                           double speed, double semitones, double gainDb, boolean normalize) {

    /** 什么都不做：不裁、不变速、不变调、原音量 */
    public static final VoiceOptions NONE = new VoiceOptions(0, 0, 1, 0, 0, false);

    public VoiceOptions {
        if (startSeconds < 0) {
            throw new IllegalArgumentException("startSeconds 不能为负：" + startSeconds);
        }
        if (durationSeconds < 0) {
            throw new IllegalArgumentException("durationSeconds 不能为负：" + durationSeconds);
        }
        if (speed < 0.5 || speed > 2.0) {
            throw new IllegalArgumentException("speed 必须在 0.5~2.0 之间：" + speed);
        }
        if (semitones < -12 || semitones > 12) {
            throw new IllegalArgumentException("semitones 必须在 -12~12 之间：" + semitones);
        }
        if (gainDb < -30 || gainDb > 30) {
            throw new IllegalArgumentException("gainDb 必须在 -30~30 之间：" + gainDb);
        }
    }

    /** 有没有任何一项需要动音频。全默认时可以把 ffmpeg 那一步整个跳过吗？不能 —— 还需要转 24k mp3 */
    public boolean isPassthrough() {
        return startSeconds == 0 && durationSeconds == 0 && speed == 1
                && semitones == 0 && gainDb == 0 && !normalize;
    }

    public static Builder builder() {
        return new Builder();
    }

    /** 链式构造。参数校验在 {@link VoiceOptions} 的构造器里，这里只负责收集 */
    public static final class Builder {

        private double startSeconds;
        private double durationSeconds;
        private double speed = 1;
        private double semitones;
        private double gainDb;
        private boolean normalize;

        /** 从第几秒开始（0 = 从头） */
        public Builder start(double seconds) {
            this.startSeconds = seconds;
            return this;
        }

        /** 取多长（0 = 到结尾） */
        public Builder duration(double seconds) {
            this.durationSeconds = seconds;
            return this;
        }

        /** 语速倍率，0.5~2.0，不变调 */
        public Builder speed(double factor) {
            this.speed = factor;
            return this;
        }

        /** 变调半音数，-12~12。正数升调，负数降调 */
        public Builder semitones(double value) {
            this.semitones = value;
            return this;
        }

        /** 音量增减（dB），-30~30 */
        public Builder gainDb(double value) {
            this.gainDb = value;
            return this;
        }

        /** 响度归一化到 -16 LUFS，群里听起来不会忽大忽小 */
        public Builder normalize(boolean value) {
            this.normalize = value;
            return this;
        }

        public VoiceOptions build() {
            return new VoiceOptions(startSeconds, durationSeconds, speed, semitones, gainDb, normalize);
        }
    }
}
