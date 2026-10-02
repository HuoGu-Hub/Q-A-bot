package com.example.qqbot.publicapi;

import com.example.qqbot.config.SiteProperties;
import com.example.qqbot.site.CarouselService;
import com.example.qqbot.site.CarouselStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 公开站的首页轮播接口。
 *
 * <p>为什么单独一个控制器、而不是塞进 {@code PublicController}：
 * 那个类管的是**知识库检索**那一摊（术语、语料、检索、限流），
 * 而这里是"站点配图"这类静态内容 —— 两条完全不同的业务线，
 * 混在一个 400 多行的类里以后只会更难找。
 *
 * <p><b>只读、只吐公开字段</b>（和 {@code PublicController} 同样的约束）：
 * 不带磁盘文件名、不带 mime、不带创建时间 —— 群友不需要也不该知道这些。
 * 认证不需要（首页本来就要给所有人看），Nginx 那层另有速率限制；
 * 图片请求单独给了长缓存 + 独立 location，不吃 API 那份配额。
 */
@RestController
@RequestMapping("/api/public/carousel")
public class PublicCarouselController {

    private static final Logger log = LoggerFactory.getLogger(PublicCarouselController.class);

    private final CarouselStore store;
    private final CarouselService service;
    private final SiteProperties site;

    public PublicCarouselController(CarouselStore store, CarouselService service, SiteProperties site) {
        this.store = store;
        this.service = service;
        this.site = site;
    }

    /**
     * 轮播图清单（只含启用中的，按后台设的顺序）。
     *
     * <p>总开关关掉、或者库里还没有图时，返回空数组而不是报错 ——
     * 首页那边看到空数组就不渲染这一块。
     */
    @GetMapping("")
    public Map<String, Object> list() {
        SiteProperties.Carousel cfg = site.getCarousel();
        List<Map<String, Object>> items = new ArrayList<>();
        if (cfg.isEnabled() && store.isAvailable()) {
            for (CarouselStore.Item it : store.listEnabled()) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", it.id());
                m.put("imageUrl", "/api/public/carousel/image/" + it.id()
                        + "?v=" + CarouselService.versionOf(it));
                m.put("link", it.link());
                m.put("caption", it.caption());
                m.put("width", it.width());
                m.put("height", it.height());
                items.add(m);
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("intervalMs", service.publicIntervalMs());
        out.put("items", items);
        return out;
    }

    /**
     * 图片字节。
     *
     * <p><b>immutable 长缓存</b>：同一个 id 的内容永远不会变（要换图就删掉重传，
     * 重传后 URL 上的 {@code ?v=} 会变）。这条 + Nginx 那边的独立 location，
     * 让首页刷新时图片走浏览器缓存，既不打扰后端也不吃公开 API 的限流配额。
     */
    @GetMapping("/image/{id}")
    public ResponseEntity<byte[]> image(@PathVariable long id) {
        try {
            CarouselService.ImageData data = service.read(id);
            if (data == null) {
                return ResponseEntity.notFound().build();
            }
            return ResponseEntity.ok()
                    .header(HttpHeaders.CACHE_CONTROL, "public, max-age=31536000, immutable")
                    .contentType(MediaType.parseMediaType(data.mime()))
                    .body(data.bytes());
        } catch (IOException e) {
            log.warn("[SITE] 读取轮播图失败：id={} {}", id, e.getMessage());
            return ResponseEntity.internalServerError().build();
        }
    }
}
