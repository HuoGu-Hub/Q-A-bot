package com.example.qqbot.voice;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * edge-tts 合成 —— 调外部命令，不常驻进程。
 *
 * <h2>为什么不嵌一个 Python 服务</h2>
 * 每句话起一个子进程，约 0.4 秒启动开销，换来的是"没有常驻进程、不用管端口、
 * 崩了不影响机器人"。群里语音回复本来就要等 1~2 秒网络，这点开销可以接受。
 *
 * <h2>为什么输出直接落成 mp3</h2>
 * edge-tts 给的就是 24kHz 单声道 mp3，和语音条的参数完全对得上，
 * 不需要再过一遍 ffmpeg；缓存里存的也是它。
 *
 * <h2>失败语义</h2>
 * 一律抛 {@link VoiceException}。调用方（工具或兜底）据此决定退回文字，
 * 不允许静默发一条空语音。
 */
@Component
public class EdgeTtsSynthesizer implements SpeechSynthesizer {

    private static final Logger log = LoggerFactory.getLogger(EdgeTtsSynthesizer.class);

    /** 中文声线。给后台下拉框用，不在运行时探测 —— 探测一次要联网两秒 */
    public static final List<String> ZH_VOICES = List.of(
            "zh-CN-XiaoxiaoNeural",
            "zh-CN-XiaoyiNeural",
            "zh-CN-YunxiNeural",
            "zh-CN-YunyangNeural",
            "zh-CN-YunjianNeural",
            "zh-CN-YunxiaNeural",
            "zh-CN-liaoning-XiaobeiNeural",
            "zh-CN-shaanxi-XiaoniNeural");

    private final TtsPolicy policy;
    private final VoiceCache cache;

    public EdgeTtsSynthesizer(TtsPolicy policy, VoiceCache cache) {
        this.policy = policy;
        this.cache = cache;
    }

    @Override
    public boolean isAvailable() {
        if (!policy.isTtsEnabled()) {
            return false;
        }
        if (!"edge-tts".equalsIgnoreCase(policy.getTtsProvider())) {
            return false;
        }
        String path = policy.getEdgeTtsPath();
        if (path == null || path.isBlank()) {
            return false;
        }
        // 只有写成路径时才检查存在性；只写命令名时交给 PATH
        if (path.contains("/") || path.contains("\\")) {
            return Files.isExecutable(Path.of(path)) || Files.isRegularFile(Path.of(path));
        }
        return true;
    }

    @Override
    public List<String> voices() {
        return ZH_VOICES;
    }

    @Override
    public String defaultVoice() {
        return policy.getTtsVoice();
    }

    public byte[] synthesize(String text) {
        return synthesize(text, null);
    }

    /**
     * 合成一段话。命中缓存直接返回，不启动进程。
     *
     * @param voice 指定声线；为空则用配置里的默认声线
     */
    @Override
    public byte[] synthesize(String text, String voice) {
        if (!policy.isTtsEnabled()) {
            throw new VoiceException("语音合成已关闭（app.voice.tts.enabled=false）");
        }
        if (text == null || text.isBlank()) {
            throw new VoiceException("要合成的内容为空");
        }
        String spoken = text.trim();
        int max = policy.getTtsMaxChars();
        if (max > 0 && spoken.length() > max) {
            spoken = spoken.substring(0, max);
            log.info("[VOICE] 合成文本超过 {} 字，已截断", max);
        }
        String useVoice = voice == null || voice.isBlank() ? policy.getTtsVoice() : voice;
        String key = cacheKey(spoken, useVoice);

        byte[] cached = cache.get(key);
        if (cached != null) {
            log.info("[VOICE] TTS 命中缓存：{} 字，{} 字节", spoken.length(), cached.length);
            return cached;
        }

        Path out = null;
        try {
            Path dir = Path.of(policy.getTtsCacheDir()).toAbsolutePath().normalize();
            Files.createDirectories(dir);
            out = Files.createTempFile(dir, "tts-", ".mp3");

            List<String> cmd = List.of(
                    policy.getEdgeTtsPath(),
                    "--voice", useVoice,
                    "--rate=" + policy.getTtsRate(),
                    "--pitch=" + policy.getTtsPitch(),
                    "--volume=" + policy.getTtsVolume(),
                    "--text", spoken,
                    "--write-media", out.toString());
            String stderr = run(cmd, policy.getTtsTimeoutSeconds());
            if (!Files.isRegularFile(out) || Files.size(out) == 0) {
                throw new VoiceException("合成没有产出音频：" + tail(stderr));
            }
            byte[] bytes = Files.readAllBytes(out);
            cache.put(key, bytes);
            log.info("[VOICE] TTS 合成完成：{} 字 → {} 字节（{}）", spoken.length(), bytes.length, useVoice);
            return bytes;
        } catch (IOException e) {
            throw new VoiceException("合成失败（读写临时文件）：" + e.getMessage(), e);
        } finally {
            if (out != null) {
                try {
                    Files.deleteIfExists(out);
                } catch (IOException ignored) {
                    // 临时文件交给缓存目录的容量清理
                }
            }
        }
    }

    private static String run(List<String> cmd, int timeoutSeconds) {
        Process process;
        try {
            process = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        } catch (IOException e) {
            throw new VoiceException("启动 edge-tts 失败，检查 app.voice.tts.edge-tts-path："
                    + e.getMessage(), e);
        }
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        Thread reader = new Thread(() -> {
            try (InputStream in = process.getInputStream()) {
                in.transferTo(buffer);
            } catch (IOException ignored) {
                // 进程被杀的读流异常没有可处理的东西
            }
        }, "edge-tts-reader");
        reader.setDaemon(true);
        reader.start();

        try {
            int seconds = Math.max(5, timeoutSeconds);
            if (!process.waitFor(seconds, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new VoiceException("edge-tts 超时（" + seconds + " 秒），已终止");
            }
            reader.join(1000);
            String output = buffer.toString(StandardCharsets.UTF_8);
            if (process.exitValue() != 0) {
                throw new VoiceException("edge-tts 退出码 " + process.exitValue() + "：" + tail(output));
            }
            return output;
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new VoiceException("edge-tts 被中断", e);
        }
    }

    /** 缓存键：文本 + 声线 + 三个参数，任何一项不同都是另一段音频 */
    private String cacheKey(String text, String voice) {
        String raw = text + "|" + voice + "|" + policy.getTtsRate() + "|"
                + policy.getTtsPitch() + "|" + policy.getTtsVolume();
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new VoiceException("当前 JVM 没有 SHA-256", e);
        }
    }

    private static String tail(String text) {
        if (text == null || text.isBlank()) {
            return "（无输出）";
        }
        String trimmed = text.trim();
        return trimmed.length() <= 300 ? trimmed : trimmed.substring(trimmed.length() - 300);
    }

    @Override
    public String toString() {
        return "EdgeTtsSynthesizer[" + policy.getTtsVoice() + "]";
    }
}
