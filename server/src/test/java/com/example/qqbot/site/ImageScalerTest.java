package com.example.qqbot.site;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ImageScaler} 的纯逻辑测试。
 *
 * <p>不需要磁盘、不需要 Spring —— 它被单独拆出来就是为了能这样直接测。
 */
class ImageScalerTest {

    @Test
    @DisplayName("worthScaling：目标更大或不合法时不缩")
    void worthScalingBoundaries() {
        assertThat(ImageScaler.worthScaling(2560, 1280)).isTrue();
        assertThat(ImageScaler.worthScaling(2560, 2560)).as("等于原宽没必要缩").isFalse();
        assertThat(ImageScaler.worthScaling(800, 1280)).as("放大会糊，不做").isFalse();
        assertThat(ImageScaler.worthScaling(2560, 0)).as("0 表示要原图").isFalse();
        assertThat(ImageScaler.worthScaling(0, 640)).as("原宽未知时不敢乱缩").isFalse();
    }

    @Test
    @DisplayName("scale：宽度精确到位，高度按原比例取整，至少 1 像素")
    void scaleKeepsRatio() {
        BufferedImage src = new BufferedImage(2560, 1440, BufferedImage.TYPE_INT_RGB);

        BufferedImage half = ImageScaler.scale(src, 1280);
        assertThat(half.getWidth()).isEqualTo(1280);
        assertThat(half.getHeight()).isEqualTo(720);

        BufferedImage quarter = ImageScaler.scale(src, 640);
        assertThat(quarter.getWidth()).isEqualTo(640);
        assertThat(quarter.getHeight()).isEqualTo(360);

        // 极端窄条也不能算出 0 高：BufferedImage 会直接抛异常
        BufferedImage sliver = ImageScaler.scale(new BufferedImage(1000, 3000, BufferedImage.TYPE_INT_RGB), 64);
        assertThat(sliver.getHeight()).isGreaterThanOrEqualTo(1);
    }

    @Test
    @DisplayName("★ hasVisibleAlpha：带通道但全不透明要判 false —— 照片存 PNG 正是这一种")
    void distinguishesRealAlphaFromOpaqueChannel() {
        BufferedImage opaqueArgb = new BufferedImage(20, 20, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = opaqueArgb.createGraphics();
        try {
            g.fillRect(0, 0, 20, 20);
        } finally {
            g.dispose();
        }
        assertThat(opaqueArgb.getColorModel().hasAlpha()).as("它确实有 alpha 通道").isTrue();
        assertThat(ImageScaler.hasVisibleAlpha(opaqueArgb))
                .as("但每个像素都是 255，按不透明处理，否则大 PNG 永远省不下来").isFalse();

        BufferedImage noAlpha = new BufferedImage(20, 20, BufferedImage.TYPE_INT_RGB);
        assertThat(ImageScaler.hasVisibleAlpha(noAlpha)).isFalse();

        BufferedImage realAlpha = new BufferedImage(20, 20, BufferedImage.TYPE_INT_ARGB);
        realAlpha.setRGB(3, 7, 0x003366CC);   // 一个透明像素就够
        assertThat(ImageScaler.hasVisibleAlpha(realAlpha)).isTrue();
    }

    @Test
    @DisplayName("encodeJpeg：ARGB 也能编（JPEG 编码器本身不接受带 alpha 的类型）")
    void encodesArgbAsJpeg() throws Exception {
        BufferedImage argb = new BufferedImage(40, 30, BufferedImage.TYPE_INT_ARGB);
        argb.setRGB(1, 1, 0x80FF0000);

        byte[] jpeg = ImageScaler.encodeJpeg(argb, 0.82f);

        assertThat(jpeg.length).isGreaterThan(0);
        assertThat(jpeg[0] & 0xFF).as("JPEG 魔数").isEqualTo(0xFF);
        assertThat(jpeg[1] & 0xFF).isEqualTo(0xD8);
    }
}