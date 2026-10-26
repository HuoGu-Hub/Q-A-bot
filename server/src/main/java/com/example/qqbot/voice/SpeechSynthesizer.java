package com.example.qqbot.voice;

import java.util.List;

/**
 * 文字转语音的**唯一接口**。
 *
 * <p>现在只有 edge-tts 一个实现，但调用方只认这个接口：
 * 以后换 Azure、换本地模型，工具和兜底逻辑一行都不用改。
 */
public interface SpeechSynthesizer {

    /** 当前实现是否可用。不可用时调用方应直接发文字 */
    boolean isAvailable();

    /** 可选的声线列表。给后台下拉框用，不做运行时探测 */
    List<String> voices();

    /** 配置里的默认声线 */
    String defaultVoice();

    /** 用默认声线合成 */
    byte[] synthesize(String text);

    /** 用指定声线合成。{@code voice} 为空时用默认声线 */
    byte[] synthesize(String text, String voice);
}
