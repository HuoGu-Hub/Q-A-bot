package com.example.qqbot.publicapi;

import com.example.qqbot.site.CarouselService;
import com.example.qqbot.site.CarouselStore;
import com.example.qqbot.site.SitePolicy;
import com.example.qqbot.web.ApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 轮播图片接口的契约测试。
 *
 * <p>不起 Spring MVC，直接调控制器方法 —— 验的是"查询参数怎么解、响应头长什么样"这两件事。
 *
 * <p>重点在 {@code ?w=}：它只影响清晰度与体积，不影响正确性。
 * 前端是拼字符串拼出来的，写错一个值不该让整张图变成错误页。
 */
class PublicCarouselControllerTest {

    private CarouselService service;
    private PublicCarouselController controller;

    @BeforeEach
    void setUp() {
        service = mock(CarouselService.class);
        controller = new PublicCarouselController(mock(CarouselStore.class), service, mock(SitePolicy.class));
    }

    private static CarouselService.ImageData data(byte value, String mime) {
        return new CarouselService.ImageData(new byte[]{value}, mime);
    }

    @Test
    @DisplayName("?w= 原样交给服务端去缩，响应头保持 immutable 长缓存")
    void passesWidthThrough() throws Exception {
        when(service.read(7L, 1280)).thenReturn(data((byte) 1, "image/jpeg"));

        ResponseEntity<byte[]> res = controller.image(7L, "1280");

        assertThat(res.getStatusCode().value()).isEqualTo(200);
        assertThat(res.getBody()).containsExactly((byte) 1);
        assertThat(res.getHeaders().getContentType().toString()).isEqualTo("image/jpeg");
        assertThat(res.getHeaders().getCacheControl())
                .as("按宽度缩放后仍然是同一个 id 的内容，长缓存不能丢").contains("immutable");
    }

    @Test
    @DisplayName("★ ?w= 写错不报 400：统统退回原图")
    void badWidthFallsBackToOriginal() throws Exception {
        when(service.read(7L, 0)).thenReturn(data((byte) 9, "image/png"));
        when(service.read(7L, 640)).thenReturn(data((byte) 8, "image/png"));

        assertThat(controller.image(7L, null).getBody()).as("没传").containsExactly((byte) 9);
        assertThat(controller.image(7L, "").getBody()).as("空串").containsExactly((byte) 9);
        assertThat(controller.image(7L, "abc").getBody()).as("不是数字").containsExactly((byte) 9);
        assertThat(controller.image(7L, "-1").getBody()).as("负数").containsExactly((byte) 9);
        assertThat(controller.image(7L, " 640 ").getBody()).as("前后空格要容忍").containsExactly((byte) 8);
    }

    @Test
    @DisplayName("图片不存在回 404，而不是 500")
    void missingImageIsNotFound() throws Exception {
        when(service.read(9L, 0)).thenReturn(null);

        assertThatThrownBy(() -> controller.image(9L, null))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.status().value()).isEqualTo(404));
    }
}