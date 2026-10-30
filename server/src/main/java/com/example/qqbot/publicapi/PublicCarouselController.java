package com.example.qqbot.publicapi;

import io.swagger.v3.oas.annotations.tags.Tag;

import com.example.qqbot.web.ApiException;

import com.example.qqbot.site.SitePolicy;
import com.example.qqbot.site.Carousel;
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
import org.springframework.web.bind.annotation.RequestParam;
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
@Tag(name = "Public")
@RestController
@RequestMapping("/api/public/carousel")
public class PublicCarouselController {

    private static final Logger log = LoggerFactory.getLogger(PublicCarouselController.class);

    private final CarouselStore store;
    private final CarouselService service;
    private final SitePolicy site;

    public PublicCarouselController(CarouselStore store, CarouselService service, SitePolicy site) {
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
        Carousel cfg = site.getCarousel();
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
     *
     * <p><b>{@code ?w=}</b> 取目标宽度，服务端按需缩放并在磁盘上缓存结果。
     * 不传就是原图 —— 老链接、后台预览、第三方引用都不会因为这次改动而变样。
     * 前端用 {@code srcset} 一次给出几档，浏览器按屏幕密度和布局自己挑。
     */
    @GetMapping("/image/{id}")
    public ResponseEntity<byte[]> image(@PathVariable long id,
                                        @RequestParam(name = "w", required = false) String w) {
        try {
            CarouselService.ImageData data = service.read(id, parseWidth(w));
            if (data == null) {
                throw ApiException.notFound("资源不存在");
            }
            return ResponseEntity.ok()
                    .header(HttpHeaders.CACHE_CONTROL, "public, max-age=31536000, immutable")
                    .contentType(MediaType.parseMediaType(data.mime()))
                    .body(data.bytes());
        } catch (IOException e) {
            log.warn("[SITE] 读取轮播图失败：id={} {}", id, e.getMessage());
            throw ApiException.internal("服务端处理失败");
        }
    }

    /**
     * 解析 {@code ?w=}。**任何异常输入都退回原图**，不报 400。
     *
     * <p>这个参数只影响清晰度与体积，不影响正确性；为了它把整张图变成错误页，
     * 代价远大于收益。真正的范围校验在服务层，这里只负责"是不是个整数"。
     */
    private static int parseWidth(String w) {
        if (w == null || w.isBlank()) {
            return 0;
        }
        try {
            int parsed = Integer.parseInt(w.trim());
            // 负数与 0 一样表示"不用缩"：把判断收在这一个函数里，
            // 服务层就只需要认"0 = 原图，正数 = 目标宽度"这一种约定。
            return Math.max(0, parsed);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
