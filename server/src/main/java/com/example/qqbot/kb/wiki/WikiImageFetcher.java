package com.example.qqbot.kb.wiki;

import java.util.Optional;

/**
 * 把一页的主图变成 marker 的 key —— 导入器只依赖这个动作，不依赖"怎么下载"。
 *
 * <h2>为什么收成接口</h2>
 * 导入器的测试要能在**不碰网络、不建目录**的情况下跑完。有了这个接口，
 * 测试传一个返回 empty 的实现即可；生产实现是 {@link WikiImageStore}。
 *
 * <p>失败一律返回 empty，不抛异常：一张图抓不到，不该让整页正文导不进去。
 */
public interface WikiImageFetcher {

    /**
     * @param key        marker 里冒号之后的部分，形如 {@code page-slug/file-slug.png}
     * @param downloaded true = 本次真的下载了；false = 复用磁盘上已有的那份
     */
    record Result(String key, boolean downloaded) {
    }

    /**
     * 取一页的主图并落盘。
     *
     * @param pageTitle 页面标题，用来决定落在哪个目录
     * @param pick      {@link WikiImagePicker} 认出来的主图
     * @return 没有图、抓取失败、配额已满时返回 empty
     */
    Optional<Result> fetch(String pageTitle, WikiImagePicker.Pick pick);
}
