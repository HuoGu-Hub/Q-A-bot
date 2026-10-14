/**
 * 地图点位的配色 —— 按**分类**给色，不按分组。
 *
 * <h2>为什么按分类而不是按分组</h2>
 * wiki 有 40 多个分组（火焰神龛、敌营、矿坑、化石……），给 40 多个颜色既分不清也不好看。
 * 而玩家真正要一眼分出来的是**这几类**：这是资源，那是敌人据点，那是可当地标的大地方。
 * 分类只有七个，七个颜色是能记住的。
 *
 * <h2>颜色从哪来</h2>
 * 全部取自站点的既有色板（灵火橙 / 苔藓绿 / 中性蓝灰 / 琥珀 / 灵火亮），
 * 只补了两个同调的中低饱和色（npc 的柔蓝、region 的柔紫）。
 * 地图是数据可视化，允许比界面多用几个色相，但不该跳出一个完全不同的色彩体系。
 *
 * <h2>它是主要依据吗</h2>
 * 不是。**分辨具体点位靠图标**（每个分组有自己的 wiki 图标）。
 * 颜色只在两种时候起作用：图标还没加载出来时的占位圆点，以及侧栏的图例。
 */
export const CATEGORY_COLORS: Record<string, string> = {
  landmark: '#f2ab55',
  resource: '#7ea36a',
  poi: '#8b9aab',
  npc: '#7fb3d5',
  collection: '#cfa334',
  region: '#b58fd6',
  custom: '#ffd08a',
}

/** 认不出的分类用中性蓝灰 —— 不猜，也不给一个刺眼的颜色 */
export const CATEGORY_FALLBACK = '#8b9aab'

export function categoryColor(category?: string | null): string {
  if (!category) {
    return CATEGORY_FALLBACK
  }
  return CATEGORY_COLORS[category] ?? CATEGORY_FALLBACK
}
