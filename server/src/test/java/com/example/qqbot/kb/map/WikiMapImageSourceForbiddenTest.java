package com.example.qqbot.kb.map;

import com.example.qqbot.kb.MapOptions;
import com.example.qqbot.media.UpstreamGate;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** 403 兜底：Special:FilePath 被 WAF 拒时，自动改走 /images/ 直链。 */
class WikiMapImageSourceForbiddenTest {

    private HttpServer server;
    private final AtomicInteger primaryHits = new AtomicInteger();
    private final AtomicInteger directHits = new AtomicInteger();

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/wiki/Special:FilePath/", exchange -> {
            primaryHits.incrementAndGet();
            byte[] body = "blocked".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(403, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.createContext("/images/", exchange -> {
            directHits.incrementAndGet();
            byte[] body = new byte[]{1, 2, 3, 4};
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    @DisplayName("★ 主路径 403 时只请求一次，直链成功取回字节")
    void fallsBackToDirect() throws Exception {
        int port = server.getAddress().getPort();
        MapOptions options = new MapOptions();
        options.setImageBase("http://127.0.0.1:" + port + "/wiki/Special:FilePath/");
        options.setImageDirectBase("http://127.0.0.1:" + port + "/images/");

        WikiMapImageSource source = new WikiMapImageSource(options, new UpstreamGate(0, 0, 1));

        assertThat(source.fetch("2_7.jpg", 640)).containsExactly(1, 2, 3, 4);
        assertThat(primaryHits).hasValue(1);
        assertThat(directHits).hasValue(1);
    }
}
