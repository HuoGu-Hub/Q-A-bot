package com.example.qqbot.voice;

/**
 * TTS 的策略 —— 与 {@link VoicePolicy} 分开，因为它只管"怎么把文字变成声音"。
 *
 * <p>为什么 provider 也做成配置：edge-tts 是逆向接口，随时可能变；
 * 换 Azure 或本地模型时只改这一项，调用方（工具、兜底逻辑）一行都不用动。
 */
public interface TtsPolicy {

    /** 总开关。关掉后调用方应该直接放弃语音，退回文字 */
    boolean isTtsEnabled();

    /** 当前 provider，目前只认 edge-tts */
    String getTtsProvider();

    /** edge-tts 可执行文件路径。Windows 上通常是 venv 里的 Scripts/edge-tts.exe */
    String getEdgeTtsPath();

    /** 默认声线，例如 zh-CN-XiaoxiaoNeural */
    String getTtsVoice();

    /** 语速，edge-tts 的写法，例如 +8% */
    String getTtsRate();

    /** 音高，例如 +0Hz */
    String getTtsPitch();

    /** 音量，例如 +0% */
    String getTtsVolume();

    /** 单次合成的字数上限。太长会拖慢、也超出语音条的合理长度 */
    int getTtsMaxChars();

    /** 合成超时（秒） */
    int getTtsTimeoutSeconds();

    /** 合成缓存目录。同一句话同一套参数只合成一次 */
    String getTtsCacheDir();
}
