/**
 * 机器人显示名 —— 全站唯一来源。
 *
 * <p>值来自后端 {@code app.bot.name}（管理后台「Bot 配置 → 机器人身份」），
 * 由 {@code GET /api/public/v1/site} 发布。公开站已经在 useSiteText 里请求该接口，
 * 管理端由本模块单独请求一次；两边共用同一个 ref，改名后刷新页面即同步。
 *
 * <p>后端不可用时保留兜底名，页面不能因为拿不到配置就出现空标题或空品牌。
 */
import { ref } from 'vue'
import { publicApi } from '@shared/api/client'

export const FALLBACK_BOT_NAME = 'Q-A-bot'

export const botName = ref(FALLBACK_BOT_NAME)

/** 把后端返回的名字写进单例；空值不覆盖兜底名 */
export function setBotName(value: string | null | undefined): void {
  const name = value?.trim()
  if (name) botName.value = name
}

let pending: Promise<void> | null = null

/** 管理端启动时拉一次；重复调用复用同一个请求 */
export function loadBotName(): Promise<void> {
  if (!pending) {
    pending = publicApi
      .get<{ botName?: string }>('/site')
      .then((r) => setBotName(r.botName))
      .catch(() => { /* 后端不可用：保留兜底名 */ })
  }
  return pending
}

/** 设置保存后重新拉取，让品牌与标题立即跟上 */
export function reloadBotName(): Promise<void> {
  pending = null
  return loadBotName()
}
