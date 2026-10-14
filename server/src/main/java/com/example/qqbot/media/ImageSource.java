package com.example.qqbot.media;

import com.example.qqbot.onebot.model.OutboundImage;

import java.util.Optional;

/**
 * 出站图片的**供给方** —— 一种图片来源实现一个。
 *
 * <h2>为什么要有这层抽象</h2>
 * 图片来源会越来越多，而且来自完全不同的包：知识库配图在 {@code media}，
 * 合成的地图截图在 {@code kb.map}。如果让发送器挨个认识它们，
 * "出站"这个协议层就会被拖进每个业务子系统的依赖里。
 *
 * <p>收成一个接口之后：各供给方自己声明 {@link #scheme()}，发送器只认 key，
 * 加一种新来源是**新增一个 Bean**，不动发送器一行。
 *
 * <p>方向也是单向的：供给方依赖 {@code media} 与 {@code onebot.model}，
 * 反过来的依赖一条都没有。
 */
public interface ImageSource {

    /**
     * 这个供给方认识的 key 前缀，例如 {@code kb} / {@code map}。
     *
     * <p>用小写、无冒号的名字 —— key 的形状是 {@code scheme:名字}。
     */
    String scheme();

    /**
     * 解析 key 里冒号之后的部分。
     *
     * <p><b>不认识的 key 一律返回 empty，不抛异常</b>：key 是模型给的，
     * 模型编一个不存在的名字是常态，不是异常。抛异常会让一次好好的回答整体失败。
     *
     * @param key 不含前缀的部分，已经过语法校验
     */
    Optional<OutboundImage> resolve(String key);
}
