package com.example.qqbot.onebot.outbound;

import com.example.qqbot.onebot.model.OutboundImage;

import java.util.List;

/**
 * 一条**已经处理好、只差发出去**的消息。
 *
 * <p>把"处理"和"发出去"拆开，是为了让发送能进队列、由专门的一个线程按节奏出队 ——
 * 处理线程不必陪着一起等发车位。
 *
 * @param group     群消息还是私聊
 * @param targetId  群号或 QQ 号
 * @param atUserId  群消息里要 @ 的人；0 = 不 @
 * @param safe      过滤之后的正文，调用方拿它记日志
 * @param parts     切好的分段；不切时就是单元素列表
 * @param images    挂在第一段上的图片
 */
public record PreparedSend(boolean group, long targetId, long atUserId,
                           String safe, List<String> parts, List<OutboundImage> images) {

    public PreparedSend {
        parts = parts == null ? List.of() : List.copyOf(parts);
        images = images == null ? List.of() : List.copyOf(images);
    }
}
