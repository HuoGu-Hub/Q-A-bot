package com.example.qqbot.kb;

import java.util.List;
import java.util.Map;

/**
 * 地点目录 —— 核心向摄入来源索要"有哪些地点"的**契约**。
 *
 * <h2>为什么契约定义在核心，而不是直接用 {@code kb.map} 的实现</h2>
 * {@code ArchitectureTest} 有一条规则：<b>kb 核心不得依赖摄入来源（wiki / map）</b>。
 * 而"哪里有 X"的算法与它的大模型工具都住在核心 —— 核心必须能拿到地点数据，
 * 又必须不认识 wiki。
 *
 * <p>仓库里已经有这个套路的先例：{@code TermSource}（词条只读视图）与
 * {@code KbCorpus}（语料只读视图）都是"契约在核心、实现在上层"。
 * 这里照做：核心定义 {@code PlaceDirectory}，{@code kb.map} 提供实现，Spring 负责注入。
 *
 * <h2>为什么接口这么薄</h2>
 * 只有三个方法，因为核心真正需要的只有三件事：有哪些点、按词找哪些点、地图多大。
 * 写 CRUD 进来会让"只读契约"名不副实 —— 写点位是管理端的事，与检索无关。
 */
public interface PlaceDirectory {

    /** 数据是否可以读（库没起来时为 false，调用方据此降级而不是报错） */
    boolean isAvailable();

    /**
     * 全部点位。**一次性给全**而不是分页：
     * 实测 wiki 全部 marker 约 1700 条，内存里就是几百 KB；
     * 而"找最近的地标"必须看全同地图的点，分页反而会把算法拆散。
     */
    List<PlacePoint> all();

    /**
     * 按词模糊找点位，按相关度返回。
     *
     * @param term  检索词（已由上层从提问里取出，例如「铁矿」）
     * @param limit 最多返回几条
     */
    List<PlacePoint> find(String term, int limit);

    /**
     * 每张地图的世界尺寸（crs 单位，{@code {宽, 高}}）。
     *
     * <p>用来把"距离"归一化成远近量级：同样是 800 单位，
     * 在 1485×1267 的黑沼是"横跨半张图"，在 10240×10240 的余烬谷是"隔壁"。
     * 取不到的地图不出现在 map 里，调用方按 0 处理（等于不做归一化）。
     */
    Map<String, double[]> worldSizes();
}
