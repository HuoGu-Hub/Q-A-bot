package com.example.qqbot.publicapi;

import com.example.qqbot.kb.map.MapResourceService;
import com.example.qqbot.kb.map.MapViewService;

import java.util.List;
import java.util.Map;

/**
 * 公开接口的数据结构（DTO）。
 *
 * <p><b>这些 record 是安全边界的一部分</b>：它们**刻意只包含可公开的字段**。
 * 管理侧的 {@code QaAnalytics.RecordRow} 有 groupId / userId / bestCosine，
 * 这里一个都没有 —— 从类型层面就不可能泄露出去。
 *
 * <p>所以公开接口**绝不复用管理接口的返回值**，即使字段"看起来差不多"。
 */
public final class PublicDtos {

    private PublicDtos() {
    }

    /**
     * 公开统计。
     *
     * <p>刻意**不含**用户数、群数、延迟分位数 —— 那些是运营指标，
     * 公开出去只会让人推断出群规模和活跃度。
     *
     * @param questions 提问量 —— 定义：@ 了机器人 + 机器人有效回应且未被拦截
     *                  （{@code guard_action='pass'}）。
     *                  ⚠️ 原来这个字段叫 {@code totalQuestions}，但取的是
     *                  {@code overview().total()}，即<b>全部群消息</b>（含群里
     *                  没 @ 机器人的闲聊）—— 对外宣称的提问数因此错了约 17 倍。
     */
    public record PublicStats(
            long questions,
            double hitRate,
            long kbEntries,
            long glossaryTerms,
            String since) {
    }

    /** 一条知识库条目 —— 内容来自公开 wiki，无隐私问题 */
    public record KbEntry(
            String title,
            String url,
            String text,
            List<String> cats) {
    }

    /**
     * 检索结果。
     *
     * @param mode semantic = 走向量路（能理解中文口语提问）；keyword = 只走了本地关键词路
     *             （术语表命中，零外部成本）。前端据此提示"语义搜索"
     */
    public record KbSearchResult(String query, int count, String mode, List<KbEntry> entries) {
    }

    /** 站点信息：公开站「关于」页用，不含任何隐私 */
    public record SiteInfo(
            String groupName,
            String groupNumber,
            String groupDesc,
            String joinHint,
            String botName,
            String org,
            String author,
            Map<String, String> texts) {
    }

    /** 一个平铺分类：公开站资料库首页 */
    public record PublicCategory(
            String key,
            String label,
            String icon,
            String desc,
            long entryCount,
            List<String> tags) {
    }

    /** 浏览列表里的一条：标题、链接、摘要、展示分类 */
    public record BrowseEntry(String title, String url, String text, List<String> tags) {
    }

    /** 分类浏览的一页 */
    public record BrowsePage(List<BrowseEntry> entries, int total, int page, int pageSize) {
    }

    /** 轮播图的一条公开视图 */
    public record CarouselItemView(
            long id,
            String imageUrl,
            String link,
            String caption,
            int width,
            int height) {
    }

    /** 轮播清单 */
    public record CarouselList(List<CarouselItemView> items, long intervalMs) {
    }

    /** 地图目录 */
    public record MapCatalog(
            boolean enabled,
            double unitsPerMeter,
            double maxZoom,
            List<MapViewService.CategoryLabel> categories,
            List<MapViewService.MapView> maps) {
    }

    /** 一张地图的点位 */
    public record MapMarkers(String map, List<MapViewService.MarkerView> markers) {
    }

    /** 资源图层目录 */
    public record ResourceCatalog(
            String map,
            boolean imported,
            String version,
            String importedAt,
            String message,
            List<MapResourceService.CategoryView> categories) {
    }

    /** 一个资源点 */
    public record ResourcePoint(double x, double z, String name, int region, String regionName) {
    }

    /** 一层资源点 */
    public record ResourcePoints(String map, String layer, List<ResourcePoint> points) {
    }

    /** 一块林地面 */
    public record ResourceArea(double x0, double z0, double x1, double z1, String name) {
    }

    /** 一层林地面 */
    public record ResourceAreas(String map, String layer, List<ResourceArea> areas) {
    }

    /** 地图搜索 */
    public record MapSearch(String q, List<MapViewService.MarkerView> results) {
    }

    /** 相对位置的锚点 */
    public record LocateAnchor(String ref, String name, double x, double y, String category) {
    }

    /** 相对位置描述 */
    public record LocateRelative(
            String direction,
            String screenHint,
            long distance,
            String bucket,
            long bearing,
            String phrase,
            LocateAnchor anchor) {
    }

    /** 一条地点结果 */
    public record LocateHit(
            String ref,
            String map,
            String canvas,
            String name,
            double x,
            double y,
            String category,
            String note,
            String source,
            LocateRelative relative) {
    }

    /** 「哪里有 X」的完整结果 */
    public record LocateResult(String q, boolean found, String text, List<LocateHit> hits) {
    }
}
