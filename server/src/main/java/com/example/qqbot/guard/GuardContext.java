package com.example.qqbot.guard;

import com.example.qqbot.onebot.model.OneBotEvent;

import java.util.List;

/**
 * 一次安全判定的上下文。
 *
 * <p>只管数据，不管逻辑 —— 这样每个 stage 都是无状态的，方便测试。
 */
public record GuardContext(OneBotEvent event, String text, List<String> imageUrls, boolean hasQuote) {

    public GuardContext {
        imageUrls = imageUrls == null ? List.of() : List.copyOf(imageUrls);
    }

    /** 群号（私聊时为 null） */
    public Long groupId() {
        return event.getGroupId();
    }

    /** 发送者 QQ 号 */
    public Long userId() {
        return event.getUserId();
    }

    /**
     * 消息里有没有「有意义的文字」。
     *
     * <p>QQ 自带表情是 face 段、图片是 image 段，都不会进到 text 里；
     * 但用户用键盘打的 Unicode emoji 是 text 段。所以这里判断的是
     * 「有没有字母/数字/汉字」——纯 emoji、纯标点都会被判为没有内容。
     */
    public boolean hasMeaningfulText() {
        if (text == null || text.isEmpty()) {
            return false;
        }
        return text.codePoints().anyMatch(Character::isLetterOrDigit);
    }

    /** 消息里有没有图片 */
    public boolean hasImage() {
        return !imageUrls.isEmpty();
    }

    /** 有没有任何可以被处理的内容（文字或图片） */
    public boolean hasContent() {
        return hasMeaningfulText() || hasImage();
    }
}
