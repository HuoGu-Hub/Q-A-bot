package com.example.qqbot.kb.map;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * 地图取图的**执行器** —— 把"等上游"这件事挪出 Tomcat 的工作线程。
 *
 * <h2>为什么必须有它</h2>
 * 一次打开地图就是几十个瓦片请求，而取图是**同步等上游**的：出站被串行节流，
 * 单张最坏要几秒到二十几秒。以前这些都发生在 Tomcat 线程里，于是
 * <b>NapCat 上报群消息用的那 100 个线程会被瓦片吃光</b> ——
 * 表现是"@ 机器人不回，连 /ping 都没反应"，而进程本身一切正常。
 *
 * <p>实测证据（2026-10-14）：日志里 {@code [http-nio-0.0.0.0-8080-exec-1]}
 * 到 {@code exec-10} 同时卡在 {@code WikiMapImageSource} 上，间隔已被 429 顶到 4000ms。
 * 事件入口 {@code /onebot/event} 走的是**同一个** Tomcat 线程池，所以它会一起没反应。
 *
 * <h2>为什么是有界队列 + 快速失败</h2>
 * 上游是串行的，排队再长也只是让用户多等几分钟才拿到一张图 ——
 * 而拒绝掉的那张图，前端下次重新渲染时会再要一次。
 * 所以队列满了就明确回 503，而不是把请求无限挂在那里。
 *
 * <h2>为什么不是 1 个线程</h2>
 * 命中缓存的请求是**纯磁盘读**，不碰上游。多几个线程它们才能并行，
 * 否则整张地图的已缓存瓦片也要排成一条线。
 */
@Service
public class MapImageWorker {

    private static final Logger log = LoggerFactory.getLogger(MapImageWorker.class);

    /** 同时在跑的取图数。只有未命中缓存的请求才会去排队等上游闸门 */
    private static final int WORKERS = 4;

    /** 排队上限。一次首屏约 64 张，留一倍余量给同时打开的人 */
    private static final int QUEUE_CAPACITY = 128;

    private final ThreadPoolExecutor pool;

    /**
     * 只留**一个**构造器。
     *
     * <p>给测试用的容量参数如果做成第二个 public 构造器，Spring 就会因为
     * "多个构造器都没 {@code @Autowired}" 而装配不了这个 Bean（见 {@code SpringWiringTest}）——
     * 那是真炸过一次的坑。测试要撑满队列时，直接把默认池灌满即可，不需要额外入口。
     */
    public MapImageWorker() {
        this.pool = new ThreadPoolExecutor(WORKERS, WORKERS, 60, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(QUEUE_CAPACITY),
                r -> {
                    Thread t = new Thread(r, "map-img");
                    t.setDaemon(true);
                    return t;
                });
    }

    /**
     * 把一次取图交给后台线程。
     *
     * @return false = 队列已满，调用方应当**立刻**给用户一个明确的失败，
     *         而不是让请求挂在这里等一个注定很慢的结果
     */
    public boolean submit(Runnable task) {
        try {
            pool.execute(task);
            return true;
        } catch (RejectedExecutionException e) {
            return false;
        }
    }

    /** 正在跑的取图数 —— 队列满时打进日志，好判断是"上游慢"还是"人太多" */
    public int inFlight() {
        return pool.getActiveCount();
    }

    /** 排队中的取图数 */
    public int queued() {
        return pool.getQueue().size();
    }

    @PreDestroy
    public void shutdown() {
        log.info("[MAP-IMG] 关闭取图线程池：在跑 {} / 排队 {}", inFlight(), queued());
        pool.shutdownNow();
    }
}
