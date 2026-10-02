package com.example.qqbot.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 启动时把**所有数据目录解析后的绝对路径**打一遍。
 *
 * <h2>为什么值得单独做一件事</h2>
 * 这个项目里 {@code app.qa.db}、{@code app.kb.dir}、{@code app.site.carousel.dir}、
 * 日志文件……全是**相对路径**，而相对路径的基准是**进程的工作目录**（不是 jar 在哪、
 * 也不是代码在哪）。于是同一份 jar：
 * <pre>
 *   cd /opt/qqbot        &amp;&amp; java -jar server/target/x.jar   →  /opt/qqbot/data/...
 *   cd /opt/qqbot/server &amp;&amp; java -jar target/x.jar          →  /opt/qqbot/server/data/...
 *   java -jar /opt/qqbot/server/target/x.jar   （在 /root 下） →  /root/data/...        ← 踩过的坑
 * </pre>
 * 更疼的是它**不报错**：目录不存在就现建一个空的 —— 你会看到"数据库空了""语料没了"，
 * 其实只是换了个地方重新开始。
 *
 * <p>所以启动时把答案直接写进日志：排查"我的数据去哪了"不用再靠猜。
 */
@Component
public class StartupPaths implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(StartupPaths.class);

    private final QaProperties qa;
    private final KbProperties kb;
    private final SiteProperties site;
    private final MediaProperties media;

    public StartupPaths(QaProperties qa, KbProperties kb, SiteProperties site, MediaProperties media) {
        this.qa = qa;
        this.kb = kb;
        this.site = site;
        this.media = media;
    }

    @Override
    public void run(ApplicationArguments args) {
        log.info("[路径] 进程工作目录：{}", abs("."));
        log.info("[路径] 问答库（统计 / 词条表 / 分类 / 文案 / 轮播清单）：{}", abs(qa.getDb()));
        log.info("[路径] 知识库目录（块表在 SQLite 里，这里只放导出的文档）：{}", abs(kb.getDir()));
        log.info("[路径] 首页轮播图片：{}", abs(site.getCarousel().getDir()));
        log.info("[路径] 知识库图片缓存：{}", abs(media.getKbImages().getDir()));
        log.info("[路径] 临时图片：{}", abs(media.getTempImages().getDir()));
        log.info("[路径] 以上都是相对**进程工作目录**解析的 —— 换个目录启动等于换了一整套数据");
    }

    private static String abs(String p) {
        try {
            Path path = Paths.get(p).toAbsolutePath().normalize();
            return path.toString();
        } catch (Exception e) {
            return p;
        }
    }
}
