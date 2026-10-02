package com.example.qqbot.logs;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 日志接口（管理后台专用）。
 *
 * <p>只服务**业务层**日志（本进程内存缓冲 + SSE 实时推送）。
 *
 * <p>原先还提供 §/admin/api/logs/napcat/**§ 去读另一个容器里 NapCat 的日志文件，
 * 已经删掉：那一层的日志对「机器人答得对不对」没有帮助，却让日志页多出一整套
 * 文件选择与分页界面。
 *
 * <p>排查顺序仍然是先看这里：
 * <pre>
 *   群里 @ 机器人
 *      ├─ 这里没有这条消息   → 上报失败 / 协议层问题
 *      ├─ 有但没回复         → Guard 拦了 / 模型挂了
 *      └─ 有回复但答得不对   → 检索或 prompt 问题
 * </pre>
 *
 * <p>认证由 {@link com.example.qqbot.admin.AdminAuthFilter} 统一拦在前面。
 */
@RestController
@RequestMapping("/admin/api/logs")
public class LogController {

    private static final Logger log = LoggerFactory.getLogger(LogController.class);

    /** SSE 推送间隔（毫秒）—— 太快会刷屏，太慢会漏掉瞬时错误 */
    private static final long PUSH_INTERVAL_MS = 800;

    private final LogBuffer buffer;
    private final LogMasker masker;
    private final LogPolicy props;

    private final AtomicInteger activeStreams = new AtomicInteger();

    public LogController(LogBuffer buffer, LogMasker masker, LogPolicy props) {
        this.buffer = buffer;
        this.masker = masker;
        this.props = props;
    }

    // ==================== 业务层日志 ====================

    /** 当前状态：缓冲里多少条、容量多少、丢了多少 */
    @GetMapping("/status")
    public Map<String, Object> status() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("enabled", props.isEnabled());
        out.put("bufferSize", buffer.size());
        out.put("capacity", buffer.capacity());
        out.put("totalWritten", buffer.totalWritten());
        out.put("dropped", buffer.dropped());
        out.put("maskSensitive", props.isMaskSensitive());
        out.put("currentSeq", buffer.currentSeq());
        out.put("activeStreams", activeStreams.get());
        return out;
    }

    /**
     * 取历史日志。
     *
     * @param limit    最多多少条（新的在后）
     * @param level    最低级别：DEBUG / INFO / WARN / ERROR
     * @param afterSeq 只要 seq 大于它的（断点续传用）
     */
    @GetMapping("/history")
    public Map<String, Object> history(@RequestParam(defaultValue = "500") int limit,
                                       @RequestParam(required = false) String level,
                                       @RequestParam(defaultValue = "0") long afterSeq) {
        List<LogBuffer.LogEntry> entries = buffer.recent(Math.min(limit, buffer.capacity()), level, afterSeq);
        List<Map<String, Object>> items = new ArrayList<>();
        for (LogBuffer.LogEntry e : entries) {
            items.add(toMap(e));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("entries", items);
        out.put("currentSeq", buffer.currentSeq());
        return out;
    }

    /**
     * 实时推送（SSE）。
     *
     * <p>用 SSE 而不是 WebSocket：只需要"服务端→客户端"单向推，
     * 而且浏览器对 SSE **原生支持断线重连**，不用自己写。
     */
    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@RequestParam(required = false) String level,
                             @RequestParam(defaultValue = "0") long afterSeq) {
        if (activeStreams.get() >= props.getMaxStreams()) {
            SseEmitter rejected = new SseEmitter(0L);
            rejected.completeWithError(new IllegalStateException(
                    "日志流连接数已达上限 " + props.getMaxStreams()));
            return rejected;
        }

        long timeoutMs = props.getStreamTimeoutMinutes() * 60_000L;
        SseEmitter emitter = new SseEmitter(timeoutMs);
        activeStreams.incrementAndGet();

        Thread worker = new Thread(() -> pump(emitter, level, afterSeq), "log-stream");
        worker.setDaemon(true);
        emitter.onCompletion(activeStreams::decrementAndGet);
        emitter.onTimeout(() -> {
            activeStreams.decrementAndGet();
            emitter.complete();
        });
        emitter.onError(e -> activeStreams.decrementAndGet());
        worker.start();
        return emitter;
    }

    /** 持续把新日志推给客户端 */
    private void pump(SseEmitter emitter, String level, long from) {
        long cursor = from > 0 ? from : buffer.currentSeq();
        try {
            // 先补一批最近的，避免刚打开是空白
            List<LogBuffer.LogEntry> initial = buffer.recent(200, level, 0);
            for (LogBuffer.LogEntry e : initial) {
                emitter.send(SseEmitter.event().name("log").data(toMap(e)));
            }
            cursor = buffer.currentSeq();

            while (true) {
                Thread.sleep(PUSH_INTERVAL_MS);
                List<LogBuffer.LogEntry> fresh = buffer.recent(0, level, cursor);
                for (LogBuffer.LogEntry e : fresh) {
                    emitter.send(SseEmitter.event().name("log").data(toMap(e)));
                    cursor = Math.max(cursor, e.seq());
                }
                // 心跳：防止中间代理把空闲连接掐掉
                emitter.send(SseEmitter.event().name("ping").data("{}"));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            emitter.complete();
        } catch (IOException | IllegalStateException e) {
            // 客户端断开是正常现象
            emitter.complete();
        } catch (Exception e) {
            log.debug("[LOGS] 日志流结束：{}", e.getMessage());
            emitter.completeWithError(e);
        } finally {
            activeStreams.decrementAndGet();
        }
    }

    @PostMapping("/clear")
    public Map<String, Object> clear() {
        buffer.clear();
        return Map.of("ok", true);
    }

    // ==================== 内部 ====================

    private Map<String, Object> toMap(LogBuffer.LogEntry e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("seq", e.seq());
        m.put("ts", e.ts());
        m.put("level", e.level());
        m.put("message", masker.mask(e.message()));
        if (e.throwable() != null) {
            m.put("throwable", masker.mask(e.throwable()));
        }
        return m;
    }

    /** 供过滤器等判断用 */
    public static ResponseEntity<?> notEnabled() {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(Map.of("error", "日志界面未启用（app.logs.enabled=false）"));
    }
}
