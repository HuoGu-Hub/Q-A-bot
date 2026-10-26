package com.example.qqbot.voice;

import jakarta.annotation.PostConstruct;
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

    /**
     * 找不到时按顺序在这些位置找 —— 都是常见的虚拟环境布局。
     *
     * <p>为什么不要求用户写绝对路径：开发机在仓库根跑、服务器在 {@code /opt/qqbot/server} 跑，
     * 同一个配置要跨两种目录结构。从工作目录逐级往上找，写相对路径就够了。
     */
    private static final List<String> LOCAL_CANDIDATES = List.of(
            ".toolchain/edge-tts/Scripts/edge-tts.exe",
            ".toolchain/edge-tts/bin/edge-tts",
            "venv/Scripts/edge-tts.exe",
            "venv/bin/edge-tts",
            ".venv/Scripts/edge-tts.exe",
            ".venv/bin/edge-tts");

    private final TtsPolicy policy;
    private final VoiceCache cache;

    /** 解析结果缓存。null 表示解析过但没找到；用 {@link #resolvedAtLeastOnce} 区分"还没解析" */
    private volatile String resolvedPath;
    private volatile boolean resolvedAtLeastOnce;

    public EdgeTtsSynthesizer(TtsPolicy policy, VoiceCache cache) {
        this.policy = policy;
        this.cache = cache;
    }

    /** 真正会被执行的命令。找不到返回 null */
    String executable() {
        if (resolvedAtLeastOnce) {
            return resolvedPath;
        }
        synchronized (this) {
            if (!resolvedAtLeastOnce) {
                resolvedPath = resolveExecutable();
                resolvedAtLeastOnce = true;
            }
            return resolvedPath;
        }
    }

    /**
     * 解析配置里的命令：
     * <ol>
     *   <li>绝对路径：存在就用，不存在直接判不可用，不做猜测；</li>
     *   <li>带目录的相对路径：相对工作目录解析；</li>
     *   <li>裸命令名：先查 PATH，再按常见虚拟环境布局逐级往上找；</li>
     *   <li>都没有：不可用，日志里说清楚找过哪里。</li>
     * </ol>
     */
    private String resolveExecutable() {
        String configured = policy.getEdgeTtsPath();
        if (configured == null || configured.isBlank()) {
            return null;
        }
        Path direct = Path.of(configured);
        if (direct.isAbsolute()) {
            return Files.isRegularFile(direct) ? direct.toString() : null;
        }
        if (configured.contains("/") || configured.contains("\\")) {
            Path relative = direct.toAbsolutePath().normalize();
            if (Files.isRegularFile(relative)) {
                return relative.toString();
            }
        } else if (onPath(configured)) {
            return configured;
        }
        // 从工作目录往上找常见布局；顺带把"相对路径"在祖先目录里再试一次
        Path dir = Path.of("").toAbsolutePath().normalize();
        for (int depth = 0; dir != null && depth < 5; depth++, dir = dir.getParent()) {
            Path relative = dir.resolve(configured);
            if (Files.isRegularFile(relative)) {
                return relative.normalize().toString();
            }
            for (String candidate : LOCAL_CANDIDATES) {
                Path hit = dir.resolve(candidate);
                if (Files.isRegularFile(hit)) {
                    return hit.normalize().toString();
                }
            }
        }
        return null;
    }

    /**
     * 启动时先探一次，把"能不能用"写进日志。
     *
     * <p>否则第一次失败发生在用户点「试听」的时候，日志里又只有一条 400，
     * 排查要从 PATH 开始猜。
     */
    @PostConstruct
    void init() {
        if (!policy.isTtsEnabled()) {
            log.info("[VOICE] TTS 已关闭（app.voice.tts.enabled=false）");
            return;
        }
        String path = policy.getEdgeTtsPath();
        if (isAvailable()) {
            log.info("[VOICE] TTS 就绪：{}（配置 {}），声线 {}，缓存 {}",
                    executable(), path, policy.getTtsVoice(), policy.getTtsCacheDir());
        } else {
            log.warn("[VOICE] TTS 不可用：配置的命令 \"{}\" 找不到。"
                    + "已尝试：PATH、工作目录及上级的 .toolchain/edge-tts、venv、.venv。"
                    + "装好后可用裸命令 edge-tts，或写相对路径 .toolchain/edge-tts/Scripts/edge-tts.exe", path);
        }
    }

    @Override
    public boolean isAvailable() {
        if (!policy.isTtsEnabled()) {
            return false;
        }
        if (!"edge-tts".equalsIgnoreCase(policy.getTtsProvider())) {
            return false;
        }
        return executable() != null;
    }

    /** 在 PATH 里找命令。Windows 还要补 .exe */
    private static boolean onPath(String command) {
        String pathEnv = System.getenv("PATH");
        if (pathEnv == null || pathEnv.isBlank()) {
            return false;
        }
        boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        for (String dir : pathEnv.split(java.io.File.pathSeparator)) {
            if (dir.isBlank()) {
                continue;
            }
            try {
                if (Files.isRegularFile(Path.of(dir, command))) {
                    return true;
                }
                if (windows && Files.isRegularFile(Path.of(dir, command + ".exe"))) {
                    return true;
                }
            } catch (Exception ignored) {
                // PATH 里有非法路径就跳过
            }
        }
        return false;
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

            String exe = executable();
            if (exe == null) {
                throw new VoiceException("找不到 edge-tts 可执行文件：配置 " + policy.getEdgeTtsPath());
            }
            List<String> cmd = List.of(
                    exe,
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
            throw new VoiceException("启动 edge-tts 失败（命令：" + cmd.get(0) + "）。"
                    + "把 .env 的 EDGE_TTS 或 app.voice.tts.edge-tts-path 指到可执行文件再试："
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
