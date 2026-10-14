package com.example.qqbot.kb.map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 底图指纹本身。
 *
 * <p>它是"点位会不会悄悄指错地方"的唯一判据，所以两件事必须成立：
 * <b>该变的要变</b>（取景窗口 / 切图基准 / 底图文件 / 制图规范），
 * 而<b>不该变的不能变</b>（同一个画布算两次必须一模一样，否则每次读都报"变了"）。
 */
class CanvasFingerprintTest {

    private static KbMapStore.Canvas canvas(String page, String schema, double tlX, double tlY,
                                           double brX, double brY, String background,
                                           double[] tileSize, double[] tileAt) {
        return new KbMapStore.Canvas("Embervale", page, schema, "", true,
                new double[]{tlX, tlY}, new double[]{brX, brY}, tileAt, tileSize, "",
                background, "", 0, 2, "2026-10-14T00:00:00Z");
    }

    private static KbMapStore.Canvas base() {
        return canvas("Map:Embervale/Background",
                "https://enshrouded.wiki.gg/extensions/DataMaps/schemas/v17.3.json",
                0, 10240, 10240, 0, "Map-Embervale.jpg",
                new double[]{1280, 1280}, new double[]{0, 0});
    }

    @Test
    @DisplayName("同一张画布算两次结果一致（否则每次读点位都会报「变了」）")
    void stableAcrossCalls() {
        assertThat(CanvasFingerprint.of(base())).isEqualTo(CanvasFingerprint.of(base()));
    }

    @Test
    @DisplayName("★ 取景窗口变了 → 指纹变，且说明里点出「取景窗口」")
    void viewportChangeIsDetected() {
        KbMapStore.Canvas moved = canvas("Map:Embervale/Background",
                "https://enshrouded.wiki.gg/extensions/DataMaps/schemas/v17.3.json",
                -1024, 11264, 11264, -1024, "Map-Embervale.jpg",
                new double[]{1280, 1280}, new double[]{0, 0});

        String before = CanvasFingerprint.of(base());
        String after = CanvasFingerprint.of(moved);

        assertThat(after).isNotEqualTo(before);
        assertThat(CanvasFingerprint.describe(before, after)).contains("取景窗口");
    }

    @Test
    @DisplayName("切图基准、底图文件、制图规范版本各自都能被识别")
    void otherPartsAreDetected() {
        String before = CanvasFingerprint.of(base());

        KbMapStore.Canvas tiled = canvas("Map:Embervale/Background",
                "https://enshrouded.wiki.gg/extensions/DataMaps/schemas/v17.3.json",
                0, 10240, 10240, 0, "", new double[]{1024, 1024}, new double[]{128, 256});
        assertThat(CanvasFingerprint.describe(before, CanvasFingerprint.of(tiled)))
                .contains("切图基准");

        KbMapStore.Canvas newImage = canvas("Map:Embervale/Background",
                "https://enshrouded.wiki.gg/extensions/DataMaps/schemas/v17.3.json",
                0, 10240, 10240, 0, "Map-Embervale-v2.jpg",
                new double[]{1280, 1280}, new double[]{0, 0});
        assertThat(CanvasFingerprint.describe(before, CanvasFingerprint.of(newImage)))
                .contains("底图文件");

        KbMapStore.Canvas newSchema = canvas("Map:Embervale/Background",
                "https://enshrouded.wiki.gg/extensions/DataMaps/schemas/v18.json",
                0, 10240, 10240, 0, "Map-Embervale.jpg",
                new double[]{1280, 1280}, new double[]{0, 0});
        assertThat(CanvasFingerprint.describe(before, CanvasFingerprint.of(newSchema)))
                .contains("制图规范");
    }

    @Test
    @DisplayName("没变化时说明是空的 —— 界面靠它决定要不要显示提示")
    void noChangeMeansEmptyReason() {
        String fp = CanvasFingerprint.of(base());
        assertThat(CanvasFingerprint.describe(fp, fp)).isEmpty();
    }

    @Test
    @DisplayName("画布找不到 / 没登记过，都算需要人看一眼")
    void missingCanvasIsFlagged() {
        String fp = CanvasFingerprint.of(base());
        assertThat(CanvasFingerprint.of(null)).isEmpty();
        assertThat(CanvasFingerprint.describe(fp, "")).contains("找不到");
        assertThat(CanvasFingerprint.describe("", fp)).contains("没登记过");
    }
}
