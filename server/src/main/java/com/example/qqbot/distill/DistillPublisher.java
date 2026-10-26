package com.example.qqbot.distill;

import com.example.qqbot.kb.block.KbBlockAdminService;
import com.example.qqbot.persistence.DistillNoteRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 把蒸馏笔记发布成知识库块。
 *
 * <p>硬规矩沿用旧提案：<b>先写块，成功了再改笔记状态。</b>
 * 反过来的话，写块失败时页面会显示"已发布"，没有人会再点第二次。
 */
@Service
public class DistillPublisher {

    private static final Logger log = LoggerFactory.getLogger(DistillPublisher.class);

    /** 蒸馏块统一放这个 docId，方便整份下架与区分 */
    public static final String DOC_ID = "chat-distill";
    /** 块 id 前缀：和 wiki 的页面 slug 不重叠，向量按 id 互不影响 */
    public static final String ID_PREFIX = "distill-";

    private final KbBlockAdminService blocks;
    private final DistillNoteRepository notes;
    private final DistillImageService images;

    public DistillPublisher(KbBlockAdminService blocks, DistillNoteRepository notes,
                            DistillImageService images) {
        this.blocks = blocks;
        this.notes = notes;
        this.images = images;
    }

    /** 发布；返回块 id。失败时笔记仍是草稿 */
    public String publish(DistillNoteRepository.Note note) {
        String blockId = ID_PREFIX + note.id();
        List<String> tags = note.category() == null || note.category().isBlank()
                ? List.of() : List.of(note.category());
        try {
            // 先复制图并把标记拼进正文；图缺失时正文照发，不让整篇发布失败
            String bodyWithImages = images.publishedBody(note);
            var existing = blocks.get(blockId);
            if (existing == null) {
                blocks.addWithSource(DOC_ID, blockId, note.title(), bodyWithImages, "", tags,
                        "chat-distill");
            } else {
                blocks.updateMeta(blockId, note.title(), bodyWithImages, tags);
                if (existing.retired()) {
                    blocks.setRetired(blockId, false);
                }
            }
            if (note.aliases() != null && !note.aliases().isBlank()) {
                blocks.updateAlias(blockId, note.aliases());
            }
            notes.updateStatus(note.id(), "published", blockId);
            log.info("[DISTILL] 笔记 {} 已发布为块 {}", note.id(), blockId);
            return blockId;
        } catch (Exception e) {
            log.warn("[DISTILL] 笔记 {} 发布失败（仍是草稿）：{}", note.id(), e.getMessage());
            throw e;
        }
    }

    /** 撤回：块下架，笔记回草稿，保留 block_id 方便再发布 */
    public void unpublish(DistillNoteRepository.Note note) {
        if (note.blockId() != null && !note.blockId().isBlank()) {
            blocks.setRetired(note.blockId(), true);
        }
        notes.updateStatus(note.id(), "draft", note.blockId());
        log.info("[DISTILL] 笔记 {} 已撤回", note.id());
    }

    /** 真删除：块与笔记一起删；这一步不可恢复 */
    public void delete(DistillNoteRepository.Note note) {
        if (note.blockId() != null && !note.blockId().isBlank()) {
            blocks.delete(note.blockId());
        }
        images.deleteImages(note.id());
        notes.delete(note.id());
        log.warn("[DISTILL] 笔记 {} 已彻底删除", note.id());
    }

    /** 只下架，不删笔记；用于批量清理 */
    public void retireBlock(DistillNoteRepository.Note note) {
        if (note.blockId() != null && !note.blockId().isBlank()) {
            blocks.setRetired(note.blockId(), true);
        }
    }
}
