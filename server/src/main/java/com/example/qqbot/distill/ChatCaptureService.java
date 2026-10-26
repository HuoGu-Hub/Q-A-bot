package com.example.qqbot.distill;

import com.example.qqbot.media.ImageFetcher;
import com.example.qqbot.onebot.codec.MessageCodec;
import com.example.qqbot.onebot.model.ImageRef;
import com.example.qqbot.onebot.model.OneBotEvent;
import com.example.qqbot.persistence.ChatRepository;
import com.example.qqbot.persistence.DistillSettingRepository;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 群聊采集 —— 把群消息异步落到 {@code chat_message}，图片同步落盘。
 *
 * <h2>为什么必须异步</h2>
 * 采集挂在 NapCat 的事件入口上。图片下载要几百毫秒，绝不能阻塞机器人回复。
 * 所以入口只做"判断 + 入队"，真正的写库与下载在单线程 worker 里。
 *
 * <p>队列有界，满了丢消息并计数：宁可少采一条，也不能拖慢回答。
 * 这个结构与 {@code QaRecorder} 一致。
 */
@Component
public class ChatCaptureService {

    private static final Logger log = LoggerFactory.getLogger(ChatCaptureService.class);

    /** 一条待落库的群消息 */
    private record Captured(long groupId, long userId, long messageId, String ts,
                            String senderHash, String senderName, String text,
                            List<ImageRef> images) {
    }

    /** 采集状态，管理页只读 */
    public record Status(boolean enabled, List<Long> groups, int queueSize,
                         long recorded, long dropped, long imageSaved) {
    }

    private final DistillPolicy props;
    private final ChatRepository repo;
    private final DistillSettingRepository settings;
    private final MessageCodec codec;
    private final ImageFetcher images;
    private final ChatImageStore imageStore;

    private BlockingQueue<Captured> queue;
    private Thread worker;
    private volatile boolean running;
    private final AtomicLong recorded = new AtomicLong();
    private final AtomicLong dropped = new AtomicLong();
    private final AtomicLong imageSaved = new AtomicLong();
    /** 只用于一次性提示"这个群没在白名单"，避免每条消息刷屏 */
    private final java.util.Set<Long> otherGroups = java.util.concurrent.ConcurrentHashMap.newKeySet();

    public ChatCaptureService(DistillPolicy props, ChatRepository repo,
                              DistillSettingRepository settings, MessageCodec codec,
                              ImageFetcher images, ChatImageStore imageStore) {
        this.props = props;
        this.repo = repo;
        this.settings = settings;
        this.codec = codec;
        this.images = images;
        this.imageStore = imageStore;
    }

    @PostConstruct
    void start() {
        if (!props.isCaptureEnabled()) {
            log.info("[DISTILL] 群聊采集未开启（app.distill.capture-enabled=false）");
            return;
        }
        if (!repo.isAvailable()) {
            log.warn("[DISTILL] 数据库不可用，群聊采集关闭");
            return;
        }
        repo.initSchema();
        queue = new ArrayBlockingQueue<>(Math.max(1, props.getQueueCapacity()));
        running = true;
        worker = new Thread(this::loop, "chat-capture");
        worker.setDaemon(true);
        worker.start();
        log.info("[DISTILL] 群聊采集已开启：群 {}，队列 {}", currentGroups(),
                props.getQueueCapacity());
    }

    @PreDestroy
    void stop() {
        running = false;
        if (worker != null) {
            worker.interrupt();
        }
    }

    /**
     * 事件入口调用。**保证立即返回、绝不抛异常**。
     */
    public void capture(OneBotEvent event) {
        if (!running || queue == null || event == null || !event.isGroupMessage()) {
            return;
        }
        try {
            long groupId = event.getGroupId() == null ? 0 : event.getGroupId();
            if (groupId <= 0) {
                return;
            }
            if (!currentGroups().contains(groupId)) {
                if (otherGroups.add(groupId)) {
                    log.info("[DISTILL] 群 {} 不在采集范围，已跳过；可在蒸馏页面添加", groupId);
                }
                return;
            }
            long userId = event.getUserId() == null ? 0 : event.getUserId();
            if (userId <= 0 || userId == (event.getSelfId() == null ? -1 : event.getSelfId())) {
                return;
            }
            String text = codec.extractPlainText(event);
            List<ImageRef> refs = codec.extractImageRefs(event);
            if ((text == null || text.isBlank()) && refs.isEmpty()) {
                return;
            }
            // 指令不进入蒸馏语料：它们不是群友的知识
            if (text != null && text.stripLeading().startsWith("/")) {
                return;
            }
            String ts = event.getTime() == null || event.getTime() <= 0
                    ? Instant.now().toString()
                    : Instant.ofEpochSecond(event.getTime()).toString();
            Captured c = new Captured(groupId, userId, event.getMessageId() == null ? 0 : event.getMessageId(),
                    ts, hash(groupId + ":" + userId), senderName(event), text == null ? "" : text.trim(),
                    refs);
            if (!queue.offer(c)) {
                long n = dropped.incrementAndGet();
                if (n == 1 || n % 100 == 0) {
                    log.warn("[DISTILL] 采集队列已满，已丢弃 {} 条", n);
                }
            }
        } catch (Exception e) {
            log.warn("[DISTILL] 采集入队失败：{}", e.getMessage());
        }
    }

    public Status status() {
        return new Status(running, currentGroups(),
                queue == null ? 0 : queue.size(), recorded.get(), dropped.get(), imageSaved.get());
    }

    /** 采集范围以页面保存的设置为准；数据库暂不可用时退回 yml 初始值 */
    private List<Long> currentGroups() {
        try {
            return settings.captureGroups();
        } catch (Exception e) {
            return props.getCaptureGroups() == null ? List.of() : props.getCaptureGroups();
        }
    }

    private void loop() {
        List<Captured> batch = new ArrayList<>();
        while (running) {
            try {
                Captured first = queue.poll(1, TimeUnit.SECONDS);
                if (first == null) {
                    continue;
                }
                batch.clear();
                batch.add(first);
                queue.drainTo(batch, Math.max(1, props.getFlushBatchSize()) - 1);
                for (Captured c : batch) {
                    persist(c);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                // worker 绝不能死：死一次采集会静默停止
                log.warn("[DISTILL] 采集线程异常（继续运行）：{}", e.getMessage());
            }
        }
    }

    private void persist(Captured c) {
        try {
            String rawHash = c.messageId() > 0
                    ? hash(c.groupId() + "|" + c.messageId())
                    : hash(c.groupId() + "|" + c.ts() + "|" + c.senderHash() + "|"
                    + c.text() + "|" + imageKeys(c.images()));
            ChatRepository.Inserted ins = repo.insertMessageIfAbsent(new ChatRepository.NewMessage(
                    "live", 0, c.groupId(), c.messageId(), c.ts(), c.senderHash(), c.senderName(),
                    c.text(), c.images().size(), rawHash, Instant.now().toString()));
            if (!ins.created() || ins.id() <= 0 || c.images().isEmpty()) {
                recorded.incrementAndGet();
                return;
            }
            int limit = Math.min(c.images().size(), Math.max(0, props.getMaxImagesPerMessage()));
            for (int i = 0; i < limit; i++) {
                saveImage(ins.id(), c.images().get(i));
            }
            recorded.incrementAndGet();
        } catch (Exception e) {
            log.warn("[DISTILL] 落库失败（跳过这条）：{}", e.getMessage());
        }
    }

    private void saveImage(long messageId, ImageRef ref) {
        try {
            var fetched = images.fetch(ref);
            if (fetched.isEmpty()) {
                return;
            }
            byte[] bytes = Base64.getDecoder().decode(fetched.get().base64());
            ChatImageStore.Stored stored = imageStore.save(bytes, fetched.get().mimeType());
            repo.insertImage(new ChatRepository.NewImage(messageId, stored.md5(), stored.filename(),
                    stored.mime(), stored.bytes(), "", Instant.now().toString()));
            imageSaved.incrementAndGet();
        } catch (IllegalArgumentException e) {
            log.warn("[DISTILL] 图片跳过：{}", e.getMessage());
        } catch (Exception e) {
            log.warn("[DISTILL] 图片保存失败：{}", e.getMessage());
        }
    }

    private static String senderName(OneBotEvent event) {
        JsonNode sender = event.getSender();
        if (sender == null || sender.isNull()) {
            return "";
        }
        String name = sender.path("card").asText("");
        if (name.isBlank()) {
            name = sender.path("nickname").asText("");
        }
        name = name.strip();
        return name.length() > 40 ? name.substring(0, 40) : name;
    }

    private static String imageKeys(List<ImageRef> refs) {
        StringBuilder sb = new StringBuilder();
        for (ImageRef r : refs) {
            sb.append(r.key() == null ? r.url() : r.key()).append('|');
        }
        return sb.toString();
    }

    private static String hash(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("计算哈希失败", e);
        }
    }
}
