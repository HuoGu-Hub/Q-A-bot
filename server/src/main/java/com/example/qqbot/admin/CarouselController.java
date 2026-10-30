package com.example.qqbot.admin;

import io.swagger.v3.oas.annotations.security.SecurityRequirement;

import io.swagger.v3.oas.annotations.tags.Tag;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import com.example.qqbot.web.ApiException;

import com.example.qqbot.site.SitePolicy;
import com.example.qqbot.site.Carousel;
import com.example.qqbot.site.CarouselService;
import com.example.qqbot.site.CarouselStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.util.unit.DataSize;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 首页轮播图的管理接口。
 *
 * <p>图片走 {@code multipart/form-data} 上传（整个项目**唯一**一处在管理端收文件的地方），
 * 其余字段是普通 JSON。校验与落盘都在 {@link CarouselService} 里，
 * 这里只负责把异常翻成人话。
 *
 * <p>认证由 {@link AdminAuthFilter} 统一拦在 {@code /admin/api/**} 前面。
 */
@Tag(name = "Admin")
@SecurityRequirement(name = "adminSession")
@RestController
@RequestMapping("/admin/api/carousel")
public class CarouselController {

    private static final Logger log = LoggerFactory.getLogger(CarouselController.class);

    /**
     * 容器的接收上限（{@code spring.servlet.multipart.max-file-size}）。
     *
     * <p>回给前端是为了让它能先说一句"这张 15 MB，服务器最多收 12 MB" ——
     * 否则用户只会看到浏览器那句 {@code Failed to fetch}。
     */
    @Value("${spring.servlet.multipart.max-file-size:12MB}")
    private DataSize uploadCeiling;

    private final CarouselStore store;
    private final CarouselService service;
    private final SitePolicy props;

    public CarouselController(CarouselStore store, CarouselService service, SitePolicy props) {
        this.store = store;
        this.service = service;
        this.props = props;
    }

    /** 清单 + 当前配置（配置也回给前端，面板上要显示"最多几张 / 上限多大"） */
    @GetMapping("")
    public Map<String, Object> list() {
        Carousel cfg = props.getCarousel();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("items", views(store.list()));
        out.put("maxCount", cfg.getMaxCount());
        out.put("maxSizeKb", cfg.getMaxSizeKb());
        out.put("uploadCeilingKb", (int) (uploadCeiling.toBytes() / 1024));
        out.put("intervalMs", service.publicIntervalMs());
        out.put("enabled", cfg.isEnabled());
        return out;
    }

    /** 上传一张 */
    @PostMapping("")
    public ResponseEntity<Map<String, Object>> upload(@RequestParam("file") MultipartFile file,
                                                     @RequestParam(value = "link", required = false) String link,
                                                     @RequestParam(value = "caption", required = false) String caption) {
        try {
            CarouselStore.Item saved = service.add(file, link, caption);
            if (saved == null) {
                throw ApiException.internal("保存失败");
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ok", true);
            out.put("item", view(saved));
            return ResponseEntity.ok(out);
        } catch (CarouselService.CarouselException e) {
            // 超张数 / 太大 / 不是图片 —— 都是用户能自己修的问题，400 + 原话
            throw ApiException.badRequest(e.getMessage());
        } catch (IOException e) {
            log.warn("[SITE] 轮播图落盘失败：{}", e.getMessage());
            throw ApiException.internal("写入图片失败：" + e.getMessage());
        }
    }

    /** 改说明 / 链接 / 启停 */
    @PatchMapping("/{id}")
    public ResponseEntity<Map<String, Object>> update(@PathVariable long id,
                                                      @RequestBody(required = false) UpdateCarouselRequest body) {
        String link = body == null ? null : body.link();
        String caption = body == null ? null : body.caption();
        Boolean enabled = body == null ? null : body.enabled();

        CarouselStore.Item updated = store.update(id, link, caption, enabled);
        if (updated == null) {
            throw ApiException.notFound("没有这张图");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("item", view(updated));
        return ResponseEntity.ok(out);
    }

    /** 上移 / 下移一位 */
    @PostMapping("/{id}/actions/move")
    public ResponseEntity<Map<String, Object>> move(@PathVariable long id,
                                                    @RequestBody(required = false) MoveRequest body) {
        int delta = body == null || body.delta() == null ? 0 : body.delta();
        if (delta == 0) {
            throw ApiException.badRequest("需要 delta = -1（上移）或 1（下移）");
        }
        if (!store.move(id, delta)) {
            // 已经在头/尾了：不是错误，前端按钮本来就该是禁用的，这里只是兜底
            throw ApiException.badRequest("已经到头了");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("items", views(store.list()));
        return ResponseEntity.ok(out);
    }

    /** 删除（连磁盘文件一起） */
    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, Object>> delete(@PathVariable long id) {
        CarouselStore.Item gone = service.delete(id);
        if (gone == null) {
            throw ApiException.notFound("没有这张图");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("items", views(store.list()));
        return ResponseEntity.ok(out);
    }

    // ==================== 工具 ====================

    /**
     * 对外的形状。
     *
     * <p>{@code imageUrl} 带一个 {@code ?v=} 版本号（用磁盘文件名算），
     * 这样"删掉再传一张"即使碰巧复用了同一个 id，URL 也变了 ——
     * 图片接口给的是 {@code immutable} 长缓存，没有这个版本号就会看到上一张的缓存。
     */
    private static Map<String, Object> view(CarouselStore.Item it) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", it.id());
        m.put("imageUrl", "/api/public/carousel/image/" + it.id() + "?v=" + CarouselService.versionOf(it));
        m.put("link", it.link());
        m.put("caption", it.caption());
        m.put("width", it.width());
        m.put("height", it.height());
        m.put("enabled", it.enabled());
        m.put("sort", it.sort());
        m.put("createdAt", it.createdAt());
        return m;
    }

    private static List<Map<String, Object>> views(List<CarouselStore.Item> items) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (CarouselStore.Item it : items) {
            out.add(view(it));
        }
        return out;
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o).trim();
    }

    @JsonIgnoreProperties(ignoreUnknown = false)

    public record UpdateCarouselRequest(String link, String caption, Boolean enabled) {
    }

    @JsonIgnoreProperties(ignoreUnknown = false)

    public record MoveRequest(Integer delta) {
    }
}
