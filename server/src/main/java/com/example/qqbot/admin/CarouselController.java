package com.example.qqbot.admin;

import com.example.qqbot.site.Carousel;
import com.example.qqbot.site.CarouselService;
import com.example.qqbot.site.CarouselStore;
import com.example.qqbot.site.SitePolicy;
import com.example.qqbot.web.ApiException;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.util.unit.DataSize;
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
import java.util.List;

/**
 * 首页轮播图的管理接口。
 */
@Tag(name = "Admin")
@SecurityRequirement(name = "adminSession")
@RestController
@RequestMapping("/admin/api/carousel")
public class CarouselController {

    private static final Logger log = LoggerFactory.getLogger(CarouselController.class);

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

    /** 清单 + 当前配置 */
    @GetMapping("")
    public CarouselAdminList list() {
        Carousel cfg = props.getCarousel();
        return new CarouselAdminList(
                views(store.list()),
                cfg.getMaxCount(),
                cfg.getMaxSizeKb(),
                (int) (uploadCeiling.toBytes() / 1024),
                service.publicIntervalMs(),
                cfg.isEnabled());
    }

    /** 上传一张 */
    @PostMapping("")
    public ResponseEntity<ItemResult> upload(@RequestParam("file") MultipartFile file,
                                             @RequestParam(value = "link", required = false) String link,
                                             @RequestParam(value = "caption", required = false) String caption) {
        try {
            CarouselStore.Item saved = service.add(file, link, caption);
            if (saved == null) {
                throw ApiException.internal("保存失败");
            }
            return ResponseEntity.ok(new ItemResult(view(saved)));
        } catch (CarouselService.CarouselException e) {
            throw ApiException.badRequest(e.getMessage());
        } catch (IOException e) {
            log.warn("[SITE] 轮播图落盘失败：{}", e.getMessage());
            throw ApiException.internal("写入图片失败：" + e.getMessage());
        }
    }

    /** 改说明 / 链接 / 启停 */
    @PatchMapping("/{id}")
    public ResponseEntity<ItemResult> update(@PathVariable long id,
                                             @RequestBody(required = false) UpdateCarouselRequest body) {
        String link = body == null ? null : body.link();
        String caption = body == null ? null : body.caption();
        Boolean enabled = body == null ? null : body.enabled();

        CarouselStore.Item updated = store.update(id, link, caption, enabled);
        if (updated == null) {
            throw ApiException.notFound("没有这张图");
        }
        return ResponseEntity.ok(new ItemResult(view(updated)));
    }

    /** 上移 / 下移一位 */
    @PostMapping("/{id}/actions/move")
    public ResponseEntity<ItemsResult> move(@PathVariable long id,
                                            @RequestBody(required = false) MoveRequest body) {
        int delta = body == null || body.delta() == null ? 0 : body.delta();
        if (delta == 0) {
            throw ApiException.badRequest("需要 delta = -1（上移）或 1（下移）");
        }
        if (!store.move(id, delta)) {
            throw ApiException.badRequest("已经到头了");
        }
        return ResponseEntity.ok(new ItemsResult(views(store.list())));
    }

    /** 删除（连磁盘文件一起） */
    @DeleteMapping("/{id}")
    public ResponseEntity<ItemsResult> delete(@PathVariable long id) {
        CarouselStore.Item gone = service.delete(id);
        if (gone == null) {
            throw ApiException.notFound("没有这张图");
        }
        return ResponseEntity.ok(new ItemsResult(views(store.list())));
    }

    public record CarouselItemView(long id, String imageUrl, String link, String caption,
                                   int width, int height, boolean enabled, int sort,
                                   String createdAt) {
    }

    public record CarouselAdminList(List<CarouselItemView> items, int maxCount, int maxSizeKb,
                                    int uploadCeilingKb, long intervalMs, boolean enabled) {
    }

    public record ItemResult(CarouselItemView item) {
    }

    public record ItemsResult(List<CarouselItemView> items) {
    }

    private static CarouselItemView view(CarouselStore.Item it) {
        return new CarouselItemView(
                it.id(),
                "/api/public/carousel/image/" + it.id() + "?v=" + CarouselService.versionOf(it),
                it.link(), it.caption(), it.width(), it.height(),
                it.enabled(), it.sort(), it.createdAt());
    }

    private static List<CarouselItemView> views(List<CarouselStore.Item> items) {
        List<CarouselItemView> out = new ArrayList<>();
        for (CarouselStore.Item it : items) {
            out.add(view(it));
        }
        return out;
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record UpdateCarouselRequest(String link, String caption, Boolean enabled) {
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record MoveRequest(Integer delta) {
    }
}
