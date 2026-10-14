package com.example.qqbot.media;

import com.example.qqbot.onebot.model.OutboundImage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 出站图片的**解析中心** —— 把回复里的 key 换成真图，顺带把三道闸门放这里。
 *
 * <h2>为什么闸门在解析中心而不是发送器</h2>
 * "这张图能不能发"是个纯媒体判断（张数、单张大小、总开关），
 * 和"这条消息发到哪个群、要不要 @"没有关系。放在这一层，将来多一个发送入口
 * 也不会绕过限制。
 *
 * <h2>为什么未知 key 只记日志</h2>
 * key 是模型写的。它编错名字、或者资料更新后图被删了，都是常态。
 * 这时**正确行为是少一张图照常回答**，而不是整条回复失败 ——
 * 一次能用的回答比一次完美的失败有用得多。
 *
 * <p>日志按 key 去重且只警告一次，避免模型反复编同一个名字把日志刷满。
 */
@Component
public class ImageLibrary {

    private static final Logger log = LoggerFactory.getLogger(ImageLibrary.class);

    private final MediaPolicy media;
    private final Map<String, ImageSource> byScheme;

    /** 已经警告过的 key：同一个名字只刷一条日志 */
    private final Set<String> warnedKeys = java.util.concurrent.ConcurrentHashMap.newKeySet();

    public ImageLibrary(MediaPolicy media, List<ImageSource> sources) {
        this.media = media;
        Map<String, ImageSource> map = new LinkedHashMap<>();
        for (ImageSource source : sources) {
            String scheme = source.scheme() == null ? "" : source.scheme().toLowerCase(java.util.Locale.ROOT);
            ImageSource previous = map.put(scheme, source);
            if (previous != null) {
                log.warn("[IMG] key 前缀 {} 有两个供给方（{} 与 {}），以后者为准",
                        scheme, previous.getClass().getSimpleName(), source.getClass().getSimpleName());
            }
        }
        this.byScheme = Map.copyOf(map);
        log.info("[IMG] 出站图片来源：{}", byScheme.isEmpty() ? "无（回复里的图片标记会被丢弃）" : byScheme.keySet());
    }

    public boolean isEnabled() {
        return media.getOutboundImages().isEnabled();
    }

    /**
     * 把一串 key 解析成可发送的图片。
     *
     * <p>会做的事，按顺序：去重（同一张图不重复发）→ 按上限截断 →
     * 逐张解析 → 丢掉解析不到的和超大的。
     *
     * @return 可以直接交给协议端的图片；没图时返回空列表，绝不返回 null
     */
    public List<OutboundImage> resolveAll(List<String> keys) {
        if (keys == null || keys.isEmpty() || !isEnabled()) {
            return List.of();
        }
        int cap = media.getOutboundImages().getMaxPerReply();
        int maxBytes = media.getOutboundImages().getMaxSizeKb() * 1024;

        List<OutboundImage> images = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (String key : keys) {
            if (!seen.add(key)) {
                continue;
            }
            if (cap > 0 && images.size() >= cap) {
                log.warn("[IMG] 一条回复里的图片超过上限 {}，丢弃：{}", cap, key);
                continue;
            }
            resolveOne(key).ifPresent(img -> {
                if (img.exceedsBytes(maxBytes)) {
                    log.warn("[IMG] 图片超过上限 {} KB，丢弃：{}（{} KB）",
                            maxBytes / 1024, key, img.byteSize() / 1024);
                    return;
                }
                images.add(img);
            });
        }
        return images;
    }

    private Optional<OutboundImage> resolveOne(String key) {
        int colon = key.indexOf(':');
        if (colon <= 0) {
            return warnOnce(key, "key 里没有来源前缀");
        }
        String scheme = key.substring(0, colon).toLowerCase(java.util.Locale.ROOT);
        String body = key.substring(colon + 1);
        ImageSource source = byScheme.get(scheme);
        if (source == null) {
            return warnOnce(key, "没有认识这个来源的供给方");
        }
        try {
            Optional<OutboundImage> image = source.resolve(body);
            if (image.isEmpty()) {
                return warnOnce(key, "供给方 " + source.getClass().getSimpleName() + " 找不到它");
            }
            return image;
        } catch (Exception e) {
            // 供给方自己出问题（磁盘、合成失败）绝不能把整条回复带走
            log.warn("[IMG] 解析图片失败，本次不带这张图：{} —— {}", key, e.getMessage());
            return Optional.empty();
        }
    }

    private Optional<OutboundImage> warnOnce(String key, String reason) {
        if (warnedKeys.add(key)) {
            log.warn("[IMG] 丢弃图片标记 {}：{}", key, reason);
        } else {
            log.debug("[IMG] 再次丢弃图片标记 {}：{}", key, reason);
        }
        return Optional.empty();
    }
}
