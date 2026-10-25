package com.example.qqbot.config;

import com.example.qqbot.kb.MapOptions;
import com.example.qqbot.media.UpstreamGate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 对 wiki 的出站闸门 —— 全局**只有一个** Bean。
 *
 * <h2>为什么要收成 Bean</h2>
 * 地图瓦片与 wiki 配图打的是同一个上游。各开一个闸门时，两边都按自己的节奏发请求，
 * 合起来正好把对方的限流额度打穿。收成一个 Bean 之后，信号量、间隔、暂停窗口都是同一份。
 *
 * <h2>为什么参数借 {@code app.kb.map.*}</h2>
 * 那组键是最早需要限流的路径，2026-10-15 的实测就是按它调出来的。新增一套
 * {@code app.upstream.*} 会让同一件事有两个旋钮，而且老部署不会跟着改。
 * 将来要独立调整时，再把它们抽出来即可。
 */
@Configuration
public class UpstreamConfig {

    @Bean
    public UpstreamGate upstreamGate(MapOptions options) {
        // 传取值函数而不是当时的值：配置中心调完立刻生效，不用重启
        return new UpstreamGate(options::getRequestGapMs, options::getRateLimitPauseMs,
                options::getRateLimitStreak);
    }
}
