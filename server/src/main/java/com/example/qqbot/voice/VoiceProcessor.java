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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * 语音加工 —— 用本机 ffmpeg 把任意音频做成"适合当语音条发"的样子。
 *
 * <h2>为什么必须过一遍 ffmpeg</h2>
 * 协议端只会整段转码，没有"从第 58 秒开始"这种参数。裁歌、变速、变调这些事
 * 只能在发出去之前做，而做这些事最稳的工具就是 ffmpeg —— 不引入 Java 音频库，
 * 免得为了一次重采样背上 PCM 解析、重采样精度、格式嗅探一堆代码。
 *
 * <h2>为什么统一输出 mp3</h2>
 * 不是为了音质，是为了**给协议端一个它一定能解的容器**。实测 SILK 直传会丢时长，
 * 而 mp3 / wav 都会走转换分支、时长由源文件量出来，最稳。24kHz 单声道 32kbps
 * 是 SILK 的实际上限，再高纯属浪费 —— 协议端反正要重新编码。
 *
 * <h2>失败为什么不静默</h2>
 * 语音是"用户主动要听"的内容，发不出去必须让调用方知道；
 * 这里抛 {@link VoiceException}，由上游决定是回一句解释还是记日志。
 */
@Component
public class VoiceProcessor {

    private static final Logger log = LoggerFactory.getLogger(VoiceProcessor.class);

    /** SILK 的实际采样率上限。输出比它高只会被协议端丢掉 */
    static final int SAMPLE_RATE = 24000;

    /** 24kHz 单声道下 32kbps 已经够用，再高听不出差别 */
    static final String BITRATE = "32k";

    private final VoicePolicy policy;

    public VoiceProcessor(VoicePolicy policy) {
        this.policy = policy;
    }

    /** 加工结果：可以直接交给协议端的 mp3 字节 + 量出来的时长 */
    public record Encoded(byte[] audio, double seconds) {
    }

    /**
     * 把一段音频加工成 24kHz 单声道 mp3。
     *
     * @param source 原始音频字节，任意 ffmpeg 能解的格式
     * @param format 扩展名提示（mp3 / wav / flac / m4a…），只用于给临时文件起名
     * @param options 加工参数，{@link VoiceOptions#NONE} 表示只转码
     */
    public Encoded encode(byte[] source, String format, VoiceOptions options) {
        if (source == null || source.length == 0) {
            throw new VoiceException("音频内容为空");
        }
        VoiceOptions opts = options == null ? VoiceOptions.NONE : options;
        Path dir = null;
        try {
            dir = Files.createTempDirectory("qqbot-voice-");
            Path input = dir.resolve("in." + safeExtension(format));
            Path output = dir.resolve("out.mp3");
            Files.write(input, source);

            List<String> cmd = new ArrayList<>();
            cmd.addAll(List.of(policy.getFfmpeg(), "-hide_banner", "-loglevel", "error", "-y",
                    "-i", input.toString()));
            String chain = filterChain(opts);
            if (!chain.isEmpty()) {
                cmd.add("-af");
                cmd.add(chain);
            }
            cmd.addAll(List.of("-ac", "1", "-ar", String.valueOf(SAMPLE_RATE),
                    "-b:a", BITRATE));
            // 时长上限放在输出侧：源文件超长时截断，不报错 —— 调用方要的是一段能听的语音
            int maxSeconds = policy.getMaxSeconds();
            if (maxSeconds > 0) {
                cmd.addAll(List.of("-t", String.valueOf(maxSeconds)));
            }
            cmd.addAll(List.of("-f", "mp3", output.toString()));

            String stderr = runAndCapture(cmd, policy.getTimeoutSeconds());
            if (!Files.exists(output)) {
                throw new VoiceException("ffmpeg 没有产出文件：" + tail(stderr));
            }
            byte[] bytes = Files.readAllBytes(output);
            if (bytes.length == 0) {
                throw new VoiceException("ffmpeg 产出的文件是空的：" + tail(stderr));
            }
            double seconds = probe(output);
            log.info("[VOICE] 加工完成：{} 字节 → {} 字节，{} 秒，参数 {}", source.length, bytes.length,
                    String.format(Locale.ROOT, "%.1f", seconds), opts);
            return new Encoded(bytes, seconds);
        } catch (IOException e) {
            throw new VoiceException("语音加工失败（读写临时文件）：" + e.getMessage(), e);
        } finally {
            deleteQuietly(dir);
        }
    }

    /**
     * 组装 ffmpeg 的音频滤镜链。顺序有讲究：
     * <ol>
     *   <li><b>先裁剪</b>：后面所有处理只需要跑真正要发的那一段；</li>
     *   <li><b>再变调</b>：变调靠改采样率实现，必须在统一的 24kHz 上算；</li>
     *   <li><b>再变速</b>：atempo 不改音高，放在变调之后可以单独调；</li>
     *   <li><b>最后调音量 / 归一化</b>：它要看到最终信号的峰值。</li>
     * </ol>
     *
     * <p>变调公式：{@code asetrate=24000*f, aresample=24000, atempo=1/f}。
     * {@code f = 2^(半音/12)}。atempo 只接受 0.5~2，超出区间就串联多级。
     */
    static String filterChain(VoiceOptions o) {
        List<String> filters = new ArrayList<>();
        if (o.startSeconds() > 0 || o.durationSeconds() > 0) {
            StringBuilder trim = new StringBuilder("atrim=start=").append(num(o.startSeconds()));
            if (o.durationSeconds() > 0) {
                trim.append(":duration=").append(num(o.durationSeconds()));
            }
            filters.add(trim.toString());
            // atrim 之后时间戳归零，否则后面的滤镜会把"原时间轴"带进输出
            filters.add("asetpts=PTS-STARTPTS");
        }
        if (o.semitones() != 0) {
            double factor = Math.pow(2, o.semitones() / 12.0);
            filters.add("aresample=" + SAMPLE_RATE);
            filters.add("asetrate=" + Math.round(SAMPLE_RATE * factor));
            filters.add("aresample=" + SAMPLE_RATE);
            appendAtempo(filters, 1.0 / factor);
        }
        if (o.speed() != 1.0) {
            appendAtempo(filters, o.speed());
        }
        if (o.gainDb() != 0) {
            filters.add("volume=" + num(o.gainDb()) + "dB");
        }
        if (o.normalize()) {
            filters.add("loudnorm=I=-16:TP=-1.5:LRA=11");
        }
        return String.join(",", filters);
    }

    /**
     * atempo 单级只能吃 0.5~2，超出的部分拆成多级。
     *
     * <p>为什么不用 rubberband：那是可选滤镜，发行版编译时不一定带。
     * atempo 是 ffmpeg 内置的，一定有。
     */
    private static void appendAtempo(List<String> filters, double factor) {
        double f = factor;
        while (f > 2.0) {
            filters.add("atempo=2.0");
            f /= 2.0;
        }
        while (f < 0.5) {
            filters.add("atempo=0.5");
            f /= 0.5;
        }
        if (Math.abs(f - 1.0) > 1e-6) {
            filters.add("atempo=" + num(f));
        }
    }

    /** 用 ffprobe 量时长。量不到就当 0：这只影响日志，不该把一条语音判死 */
    public double probe(Path file) {
        List<String> cmd = List.of(policy.getFfprobe(), "-v", "error",
                "-show_entries", "format=duration", "-of", "default=nw=1:nk=1", file.toString());
        try {
            String out = runAndCapture(cmd, policy.getTimeoutSeconds()).trim();
            return Double.parseDouble(out);
        } catch (Exception e) {
            log.warn("[VOICE] 量不出时长，按 0 记：{}", e.getMessage());
            return 0;
        }
    }

    /**
     * 跑一个外部进程并收下它的输出。
     *
     * <p>输出必须在**另一个线程**里读：ffmpeg 往管道写满 64KB 就会阻塞，
     * 而主线程正等着它退出 —— 两边互等就是死锁。
     */
    private static String runAndCapture(List<String> cmd, int timeoutSeconds) {
        Process process;
        try {
            process = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        } catch (IOException e) {
            throw new VoiceException("启动 ffmpeg 失败，请确认已安装并在 PATH 里，或配置 app.voice.ffmpeg："
                    + e.getMessage(), e);
        }
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        Thread reader = new Thread(() -> {
            try (InputStream in = process.getInputStream()) {
                in.transferTo(buffer);
            } catch (IOException ignored) {
                // 进程被杀时读流会抛，这里没有可做的事
            }
        }, "voice-ffmpeg-reader");
        reader.setDaemon(true);
        reader.start();

        try {
            int seconds = Math.max(5, timeoutSeconds);
            if (!process.waitFor(seconds, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new VoiceException("ffmpeg 超时（" + seconds + " 秒），已终止");
            }
            reader.join(1000);
            String output = buffer.toString(StandardCharsets.UTF_8);
            if (process.exitValue() != 0) {
                throw new VoiceException("ffmpeg 退出码 " + process.exitValue() + "：" + tail(output));
            }
            return output;
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new VoiceException("ffmpeg 被中断", e);
        }
    }

    /** 临时文件名里的扩展名只保留字母数字，别让调用方把路径塞进来 */
    private static String safeExtension(String format) {
        if (format == null || format.isBlank()) {
            return "audio";
        }
        String cleaned = format.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        return cleaned.isEmpty() || cleaned.length() > 5 ? "audio" : cleaned;
    }

    private static String num(double value) {
        return String.format(Locale.ROOT, "%.3f", value);
    }

    /** 错误信息只留最后一段，ffmpeg 的完整输出往往几十行，日志里没人看 */
    private static String tail(String text) {
        if (text == null || text.isBlank()) {
            return "（无输出）";
        }
        String trimmed = text.trim();
        return trimmed.length() <= 400 ? trimmed : trimmed.substring(trimmed.length() - 400);
    }

    private static void deleteQuietly(Path dir) {
        if (dir == null) {
            return;
        }
        try (var paths = Files.walk(dir)) {
            paths.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // 临时目录清不掉不影响结果，交给系统的 tmp 清理
                }
            });
        } catch (IOException ignored) {
            // 同上
        }
    }
}
