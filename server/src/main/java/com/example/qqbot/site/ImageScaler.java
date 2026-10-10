package com.example.qqbot.site;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Iterator;

/**
 * 图片等比缩小与重编码。**纯计算** —— 不碰磁盘、不碰 Spring、不认识轮播。
 *
 * <h2>为什么要单独一个类</h2>
 * 轮播图原来是<b>原样落盘、原样下发</b>的：一张 2560×1440 的照片存成 PNG 就是 6 MB，
 * 而首屏只显示到大约 600×340 —— 等于按四分之一尺寸在放大图上花带宽。
 * 实测六张合计 16.86 MB，弱网下把整条链路占满，点导航要等 20 秒以上。
 *
 * <h2>两个容易做错的地方</h2>
 * <ol>
 *   <li><b>一步缩到底会糊。</b> 2560 直接缩到 640 是四倍比，双线性采样会丢细节。
 *       这里先反复对折到目标的两倍以内，最后再一次性缩到目标 —— 视觉上明显更干净。</li>
 *   <li><b>透明通道不能想当然。</b> 只要解码结果带 alpha 就输出 PNG 的话，
 *       照片存出来的 PNG 会保持 6 MB 级别，等于白做。
 *       所以这里会真的扫一遍 alpha 通道：全不透明就按 JPEG 编码，只有真的用到了透明才留 PNG。</li>
 * </ol>
 */
final class ImageScaler {

    /** PNG 里"看不见但存在"的 alpha：照片转 PNG 绝大多数是这一种，不该因此继续发 PNG */
    private static final int FULLY_OPAQUE = 255;

    private ImageScaler() {
    }

    /** 目标宽度是否值得缩 —— 原图本来就不宽、或者目标不合法时返回 false */
    static boolean worthScaling(int srcWidth, int targetWidth) {
        return targetWidth > 0 && srcWidth > 0 && srcWidth > targetWidth;
    }

    /**
     * 等比缩到指定宽度。高度按原比例算，至少 1 像素。
     *
     * <p>先对折到目标的两倍以内再收尾：单次大比例缩放在文字、栅格、花纹上会出现明显锯齿。
     * 对折用的是双线性，收尾那一步交给渲染质量最高的路径。
     */
    static BufferedImage scale(BufferedImage src, int targetWidth) {
        int sw = src.getWidth();
        int sh = src.getHeight();
        int targetHeight = Math.max(1, (int) Math.round(sh * (double) targetWidth / sw));

        BufferedImage cur = src;
        int cw = sw;
        int ch = sh;
        while (cw / 2 >= targetWidth && ch / 2 >= targetHeight && cw > 1 && ch > 1) {
            cw = Math.max(1, cw / 2);
            ch = Math.max(1, ch / 2);
            cur = draw(cur, cw, ch);
        }
        return draw(cur, targetWidth, targetHeight);
    }

    /** 画一张新的出来。目标类型跟随原图有没有 alpha，别把透明通道悄悄压平 */
    private static BufferedImage draw(BufferedImage src, int w, int h) {
        int type = src.getColorModel().hasAlpha() ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB;
        BufferedImage out = new BufferedImage(w, h, type);
        Graphics2D g = out.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.drawImage(src, 0, 0, w, h, null);
        } finally {
            g.dispose();
        }
        return out;
    }

    /**
     * alpha 通道是不是**真的用到了**。
     *
     * <p>没有 alpha 通道 → false。有通道但每个像素都是 255 → 也 false。
     * 后者才是关键：照片另存为 PNG 时几乎都会带一个全不透明的 alpha，
     * 只看"有没有通道"会让这些图继续以 PNG 下发，省下来的空间全还回去。
     */
    static boolean hasVisibleAlpha(BufferedImage img) {
        if (!img.getColorModel().hasAlpha()) {
            return false;
        }
        int w = img.getWidth();
        int h = img.getHeight();
        int[] row = new int[w];
        for (int y = 0; y < h; y++) {
            img.getRGB(0, y, w, 1, row, 0, w);
            for (int x = 0; x < w; x++) {
                if ((row[x] >>> 24) != FULLY_OPAQUE) {
                    return true;
                }
            }
        }
        return false;
    }

    /** 按给定质量编码 JPEG。带 alpha 的图会先转成不含 alpha 的类型，否则编码器直接抛异常 */
    static byte[] encodeJpeg(BufferedImage img, float quality) throws IOException {
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpeg");
        if (!writers.hasNext()) {
            throw new IOException("运行环境没有可用的 JPEG 编码器");
        }
        ImageWriter writer = writers.next();
        ImageWriteParam param = writer.getDefaultWriteParam();
        param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
        param.setCompressionQuality(quality);

        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ImageOutputStream out = ImageIO.createImageOutputStream(bytes)) {
            writer.setOutput(out);
            writer.write(null, new IIOImage(withoutAlpha(img), null, null), param);
        } finally {
            writer.dispose();
        }
        return bytes.toByteArray();
    }

    /** 编码 PNG。只给"真的用了透明"的图走这条路 */
    static byte[] encodePng(BufferedImage img) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        if (!ImageIO.write(img, "png", bytes)) {
            throw new IOException("运行环境没有可用的 PNG 编码器");
        }
        return bytes.toByteArray();
    }

    /** JPEG 不接受 ARGB，统一转成 INT_RGB */
    private static BufferedImage withoutAlpha(BufferedImage img) {
        if (!img.getColorModel().hasAlpha()) {
            return img;
        }
        BufferedImage rgb = new BufferedImage(img.getWidth(), img.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = rgb.createGraphics();
        try {
            g.drawImage(img, 0, 0, null);
        } finally {
            g.dispose();
        }
        return rgb;
    }
}