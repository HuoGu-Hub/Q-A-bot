package com.example.qqbot.distill;

import com.example.qqbot.media.KbImageWriter;
import com.example.qqbot.persistence.ChatRepository;
import com.example.qqbot.persistence.DistillNoteRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 蒸馏笔记的配图发布。
 *
 * <p>聊天图不能直接给知识库引用：QQ 链接会过期，而且知识库协议只认
 * {@code data/kb-images} 下的文件。所以发布时把字节复制过去，正文追加
 * {@code [[img:kb:...]]}。复制结果记在 distill_note_image.kb_name，
 * 重复发布不会重复复制、也不会重复追加标记。
 */
@Service
public class DistillImageService {

    private static final Logger log = LoggerFactory.getLogger(DistillImageService.class);

    private final DistillNoteRepository notes;
    private final ChatRepository chat;
    private final ChatImageStore chatImages;
    private final KbImageWriter kbImages;

    public DistillImageService(DistillNoteRepository notes, ChatRepository chat,
                               ChatImageStore chatImages, KbImageWriter kbImages) {
        this.notes = notes;
        this.chat = chat;
        this.chatImages = chatImages;
        this.kbImages = kbImages;
    }

    /** 发布用的正文：原正文 + 图片标记；图片会先复制到 kb-images */
    public String publishedBody(DistillNoteRepository.Note note) {
        return withImages(note.id(), note.body());
    }

    public String withImages(long noteId, String body) {
        String text = body == null ? "" : body.strip();
        List<String> markers = new ArrayList<>();
        List<DistillNoteRepository.NoteImage> images = notes.imagesOf(noteId);
        for (DistillNoteRepository.NoteImage image : images) {
            String kbName = image.kbName();
            if (kbName == null || kbName.isBlank() || !kbImages.exists(kbName)) {
                kbName = copy(noteId, image.chatImageId());
                if (kbName == null) {
                    continue;
                }
                notes.setKbName(noteId, image.chatImageId(), kbName);
            }
            markers.add("[[img:kb:" + kbName + "]]");
        }
        for (String marker : markers) {
            if (!text.contains(marker)) {
                text = text.isEmpty() ? marker : text + "\n\n" + marker;
            }
        }
        return text;
    }

    private String copy(long noteId, long chatImageId) {
        ChatRepository.Image image = chat.findImage(chatImageId);
        if (image == null) {
            log.warn("[DISTILL] 笔记 {} 的聊天图 {} 已不存在，跳过", noteId, chatImageId);
            return null;
        }
        byte[] bytes = chatImages.read(image.path());
        if (bytes == null) {
            log.warn("[DISTILL] 笔记 {} 的聊天图 {} 读取失败，跳过", noteId, chatImageId);
            return null;
        }
        String mime = image.mime() == null || image.mime().isBlank()
                ? chatImages.mimeOf(image.path()) : image.mime();
        return kbImages.save(bytes, mime, "distill-" + noteId + "-" + chatImageId);
    }

    /** 彻底删除笔记时，把它复制出来的知识库图片一起删掉 */
    public void deleteImages(long noteId) {
        for (DistillNoteRepository.NoteImage image : notes.imagesOf(noteId)) {
            if (image.kbName() != null && !image.kbName().isBlank()) {
                kbImages.delete(image.kbName());
            }
        }
    }
}
