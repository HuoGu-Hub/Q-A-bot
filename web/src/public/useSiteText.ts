/**
 * 公开站「页面文案」工具。
 *
 * 后端 `GET /api/public/site` 的 `texts` 里**只有被管理员改过的块** ——
 * 没改过的键根本不会出现在 map 里。所以前端一律「保留硬编码原文当默认值」，
 * 用覆盖值兜底：
 *
 *   t('home.tagline', '在《雾锁王国》的迷雾里，问一句就好 —— 我帮你翻遍 {kb} 条资料。')
 *
 * ⚠️ 默认值（第二个参数）**不能删、也不能改成空串**：后端挂了、数据库为空、
 *    或这个 key 从没被改过时，texts 里都没有它 —— 站点必须照常用默认文案渲染，
 *    不能变成一片空白。默认值就是「站点在没有任何后端文案时该长什么样」。
 *
 * 模块级缓存：`site` 是模块单例，首页/资料库/广场/关于/404/页脚共用
 * **同一次** `/api/public/site` 请求。
 */
import { ref } from 'vue'
import { publicApi } from '@shared/api/client'

/** /api/public/site 的返回：群信息（关于页用）+ 文案覆盖值 */
export interface SiteInfo {
  groupName: string
  groupNumber: string
  groupDesc: string
  joinHint: string
  botName: string
  /** 页面文案的**覆盖值**，键是 `page.block`；没被改过的键不存在 */
  texts?: Record<string, string>
}

/** 站点信息单例（模块级）。整站只请求一次，所有页面共享同一个 ref。 */
export const site = ref<SiteInfo | null>(null)

/** 进行中的请求：非空就说明请求已经发过了，后面的人复用它 */
let pending: Promise<void> | null = null

/**
 * 拉取站点信息。重复调用只会复用同一个请求（幂等）。
 *
 * 失败**静默**：texts 保持为空 → 全部走各页面自带的默认文案。
 * 不弹错、不写 error 状态 —— 文案拿不到不该打扰用户，更不该让页面报错。
 */
export function loadSite(): Promise<void> {
  if (!pending) {
    pending = publicApi
      .get<SiteInfo>('/site')
      .then((r) => { site.value = r })
      .catch(() => { /* 后端不可用：静默失败，页面继续用默认文案渲染 */ })
  }
  return pending
}

/**
 * 取一段文案：优先用后端覆盖值，没有就用 fallback（硬编码默认值）。
 *
 * @param key      文案块 key（`page.block`），如 `home.tagline`
 * @param fallback 硬编码的当前文案，**必须保留**（见文件头说明）
 * @param params   额外占位符，如 `{ kb: 123 }`；
 *                 `{year}` 永远自动替换成当前年份，不用每处都传
 */
export function t(
  key: string,
  fallback: string,
  params: Record<string, string | number> = {},
): string {
  const raw = site.value?.texts?.[key] ?? fallback
  const vars: Record<string, string | number> = { year: new Date().getFullYear(), ...params }
  // 认不出的占位符原样留着 —— 总比被悄悄吞掉、文案缺一块好
  return raw.replace(/\{(\w+)\}/g, (m, name: string) =>
    Object.prototype.hasOwnProperty.call(vars, name) ? String(vars[name]) : m)
}

// 模块加载即发起请求：这样不依赖「哪个页面先挂载」，App.vue 一 import 就已经在路上，
// 且模块只会被求值一次 —— 天然保证整站只有一次 /site 请求。
void loadSite()
