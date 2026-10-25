package com.example.qqbot.distill;

import com.example.qqbot.kb.block.KbBlockAdminService;
import com.example.qqbot.persistence.DistillNoteRepository;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 蒸馏笔记的管理动作：编辑、发布、撤回、删除。
 *
 * <p>发布/撤回/删除都委托给 {@link DistillPublisher}，保证"块与笔记"只有一条写入顺序。
 */
@Service
public class DistillNoteService {

    public record Detail(DistillNoteRepository.Note note,
                         List<DistillNoteRepository.Source> sources,
                         List<Long> imageIds) {
    }

    private final DistillNoteRepository notes;
    private final DistillPublisher publisher;
    private final KbBlockAdminService blocks;

    public DistillNoteService(DistillNoteRepository notes, DistillPublisher publisher,
                              KbBlockAdminService blocks) {
        this.notes = notes;
        this.publisher = publisher;
        this.blocks = blocks;
    }

    public List<DistillNoteRepository.Note> list(String status, long jobId, int limit) {
        return notes.list(status, jobId, limit);
    }

    public long count(String status) {
        return status == null || status.isBlank() ? notes.count() : notes.countByStatus(status);
    }

    public Detail detail(long id) {
        DistillNoteRepository.Note n = notes.find(id);
        if (n == null) {
            return null;
        }
        return new Detail(n, notes.sourcesOf(id), notes.imageIdsOf(id));
    }

    /**
     * 编辑标题、别名、分类、正文。已发布的笔记同步更新块。
     *
     * <p>先改块、再改笔记：块改失败时笔记还是旧内容，不会出现"页面显示新内容、检索还是旧的"。
     */
    public DistillNoteRepository.Note update(long id, String title, String aliases,
                                             String category, String body, Double confidence) {
        DistillNoteRepository.Note old = notes.find(id);
        if (old == null) {
            return null;
        }
        String t = title == null ? old.title() : title.strip();
        String b = body == null ? old.body() : body.strip();
        if (t.length() < 2 || t.length() > 60) {
            throw new IllegalArgumentException("标题长度需要在 2 到 60 字之间");
        }
        if (b.length() < 10 || b.length() > 4000) {
            throw new IllegalArgumentException("正文长度需要在 10 到 4000 字之间");
        }
        String titleNorm = DistillResponseParser.normTitle(t);
        String bodyHash = DistillResponseParser.hash(DistillResponseParser.normalizeBody(b));
        String aliasesValue = aliases == null ? old.aliases() : aliases.strip();
        String cat = category == null || category.isBlank() ? old.category() : category.trim();
        double conf = confidence == null ? old.confidence() : Math.max(0, Math.min(1, confidence));

        if ("published".equals(old.status()) && old.blockId() != null && !old.blockId().isBlank()) {
            List<String> tags = cat == null || cat.isBlank() ? List.of() : List.of(cat);
            blocks.updateMeta(old.blockId(), t, b, tags);
            if (!aliasesValue.isBlank()) {
                blocks.updateAlias(old.blockId(), aliasesValue);
            }
        }
        if (!notes.updateContent(id, t, titleNorm, aliasesValue, b, bodyHash, cat, conf)) {
            throw new IllegalStateException("保存笔记失败");
        }
        return notes.find(id);
    }

    public DistillNoteRepository.Note publish(long id) {
        DistillNoteRepository.Note n = notes.find(id);
        if (n == null) {
            return null;
        }
        if ("published".equals(n.status()) && !n.blockId().isBlank()) {
            return n;
        }
        publisher.publish(n);
        return notes.find(id);
    }

    public DistillNoteRepository.Note unpublish(long id) {
        DistillNoteRepository.Note n = notes.find(id);
        if (n == null) {
            return null;
        }
        publisher.unpublish(n);
        return notes.find(id);
    }

    public boolean delete(long id) {
        DistillNoteRepository.Note n = notes.find(id);
        if (n == null) {
            return false;
        }
        publisher.delete(n);
        return true;
    }
}
