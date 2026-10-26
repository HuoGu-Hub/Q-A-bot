package com.example.qqbot.distill;

import com.example.qqbot.persistence.ChatRepository;
import com.example.qqbot.persistence.DistillJobRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 蒸馏任务：把一批消息算成批次，落库，供后台 worker 逐批处理。
 *
 * <p>本类只负责"任务与批次"，不调模型。模型执行在 M2 的 worker 里，
 * 这样建任务、预览、重试可以独立测试。
 */
@Service
public class DistillJobService {

    private static final Logger log = LoggerFactory.getLogger(DistillJobService.class);

    /** 建任务请求。可空字段由服务端用配置补齐 */
    public record JobRequest(String source, List<Long> groupIds, String timeFrom, String timeTo,
                             long importId, String batchMode, Integer batchSize,
                             Integer overlap, Integer maxGapMinutes) {
    }

    public record BatchView(int seq, long messageFrom, long messageTo, int messageCount, int charCount) {
    }

    public record Preview(int messageCount, int charCount, int batchCount, int estimatedTokens,
                          List<BatchView> batches) {
    }

    public record JobDetail(DistillJobRepository.Job job, List<DistillJobRepository.Batch> batches) {
    }

    private final ChatRepository chat;
    private final DistillJobRepository repo;
    private final DistillPolicy props;

    public DistillJobService(ChatRepository chat, DistillJobRepository repo, DistillPolicy props) {
        this.chat = chat;
        this.repo = repo;
        this.props = props;
    }

    public boolean available() {
        return chat.isAvailable() && repo.isAvailable();
    }

    /** 只预览，不写库 */
    public Preview preview(JobRequest request) {
        Normalized n = normalize(request);
        List<ChatRepository.MessageRef> messages = chat.candidates(
                n.source, n.groups, n.from, n.to, n.importId);
        if (messages.isEmpty()) {
            throw new IllegalArgumentException("没有可蒸馏的消息；先确认采集开关、群白名单或导入批次");
        }
        DistillBatchPlanner.Plan plan = DistillBatchPlanner.plan(messages,
                new DistillBatchPlanner.Options(n.mode, n.maxMessages, n.maxChars,
                        n.maxGap, n.overlap, props.getMaxImagesPerBatch(),
                        props.getMaxBatchesPerJob()));
        List<BatchView> views = new ArrayList<>();
        for (DistillBatchPlanner.Batch b : plan.batches()) {
            views.add(new BatchView(b.seq(), b.messageFrom(), b.messageTo(),
                    b.messageCount(), b.charCount()));
        }
        int estimatedTokens = (int) Math.ceil(plan.charCount() / 1.6) + plan.batches().size() * 300;
        return new Preview(plan.messageCount(), plan.charCount(), plan.batches().size(),
                estimatedTokens, views);
    }

    /** 创建任务；批次太多时直接拒绝，避免一次点爆成本 */
    public long create(JobRequest request) {
        Preview p = preview(request);
        Normalized n = normalize(request);
        String now = Instant.now().toString();
        DistillJobRepository.NewJob job = new DistillJobRepository.NewJob(
                n.source, n.groups.stream().map(String::valueOf).collect(Collectors.joining(",")),
                n.from, n.to, n.importId, n.mode, n.batchSize, n.overlap, n.maxGap,
                p.messageCount(), p.batchCount(), now);
        List<DistillJobRepository.NewBatch> batches = p.batches().stream()
                .map(b -> new DistillJobRepository.NewBatch(b.seq(), b.messageFrom(),
                        b.messageTo(), b.messageCount(), b.charCount()))
                .toList();
        long id = repo.createJob(job, batches);
        log.info("[DISTILL] 任务 {} 已创建：{} 条消息，{} 批，{} 字",
                id, p.messageCount(), p.batchCount(), p.charCount());
        return id;
    }

    public List<DistillJobRepository.Job> jobs(int limit) {
        return repo.recentJobs(limit);
    }

    public JobDetail detail(long id) {
        DistillJobRepository.Job job = repo.findJob(id);
        if (job == null) {
            return null;
        }
        return new JobDetail(job, repo.batchesOf(id));
    }

    public long countMessages() {
        return chat.countMessages();
    }

    public long countImages() {
        return chat.countImages();
    }

    public long countImports() {
        return chat.countImports();
    }

    public long countJobs() {
        return repo.countJobs();
    }

    public long countBatches() {
        return repo.countBatches();
    }

    private record Normalized(String source, List<Long> groups, String from, String to,
                              long importId, String mode, int batchSize, int maxMessages,
                              int maxChars, int maxImages, int overlap, int maxGap) {
    }

    private Normalized normalize(JobRequest r) {
        String source = r.source() == null || r.source().isBlank() ? "live" : r.source().trim();
        if (!source.equals("live") && !source.equals("file")) {
            throw new IllegalArgumentException("source 只能是 live / file");
        }
        List<Long> groups = r.groupIds() == null ? List.of() : r.groupIds();
        String from = normalizeFrom(r.timeFrom());
        String to = normalizeTo(r.timeTo());
        if (source.equals("file") && r.importId() <= 0) {
            throw new IllegalArgumentException("文件任务需要指定 importId");
        }
        String mode = r.batchMode() == null || r.batchMode().isBlank()
                ? "count" : r.batchMode().trim().toLowerCase();
        if (!mode.equals("count") && !mode.equals("chars") && !mode.equals("day")) {
            throw new IllegalArgumentException("batchMode 只能是 count / chars / day");
        }
        int defaultSize = "chars".equals(mode)
                ? props.getBatchMaxChars() : props.getBatchMaxMessages();
        int batchSize = r.batchSize() == null || r.batchSize() <= 0 ? defaultSize : r.batchSize();
        // chars 模式下 batchSize 是字数；count / day 下是条数。两种模式都另有一道硬上限。
        int maxChars = "chars".equals(mode) ? Math.max(200, batchSize) : props.getBatchMaxChars();
        int maxMessages = "chars".equals(mode)
                ? props.getBatchMaxMessages() : Math.max(1, batchSize);
        int maxImages = Math.max(1, props.getMaxImagesPerBatch());
        int overlap = r.overlap() == null ? props.getBatchOverlap() : Math.max(0, r.overlap());
        int maxGap = r.maxGapMinutes() == null
                ? props.getBatchMaxGapMinutes() : Math.max(0, r.maxGapMinutes());
        return new Normalized(source, groups, from, to, r.importId(), mode,
                Math.max(1, batchSize), maxMessages, maxChars, maxImages, overlap, maxGap);
    }

    private static String normalizeFrom(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        Instant i = ChatTime.parse(raw.trim());
        return i == null ? raw.trim() : i.toString();
    }

    private static String normalizeTo(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String s = raw.trim();
        Instant i = ChatTime.parse(s);
        if (i == null) {
            return s;
        }
        if (s.matches("\\d{4}-\\d{1,2}-\\d{1,2}")) {
            LocalDate d = i.atZone(ChatTime.ZONE).toLocalDate();
            i = d.atTime(LocalTime.MAX).atZone(ChatTime.ZONE).toInstant();
        }
        return i.toString();
    }
}
