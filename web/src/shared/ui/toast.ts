import { ref } from 'vue'

/**
 * 软弹窗（toast）—— 用于「动作之后的反馈」。
 *
 * <p><b>为什么不用内联提示条</b>：操作反馈（保存成功 / 保存失败 / 没有改动）是**瞬时**的，
 * 而内联提示条会插进页面流里，把下面的内容顶下去 —— 页面结构跟着抖一下，
 * 尤其在表格或两栏布局里会造成意想不到的位移。浮层不占布局，出现和消失都不影响别人。
 *
 * <p><b>什么该用它、什么不该</b>：
 * <ul>
 *   <li>用它：动作的结果（成功 / 失败 / 无事发生）</li>
 *   <li>别用它：需要一直看得见的说明（例如"只列真正的提问"这种口径提示），
 *       那种应该留在页面上 —— 浮层会自动消失，讲不清长期信息</li>
 * </ul>
 */
export type ToastTone = 'ok' | 'error' | 'warn' | 'info'

export interface ToastItem {
  id: number
  text: string
  tone: ToastTone
}

const items = ref<ToastItem[]>([])
let seq = 0

/** 默认停留时长：够读完一句短提示，又不至于赖着不走 */
const DEFAULT_MS = 3200

export function toast(text: string, tone: ToastTone = 'ok', ms = DEFAULT_MS): number {
  const id = ++seq
  items.value = [...items.value, { id, text, tone }]
  if (ms > 0) {
    setTimeout(() => dismiss(id), ms)
  }
  return id
}

export function dismiss(id: number) {
  items.value = items.value.filter((t) => t.id !== id)
}

/** 给 Toaster 组件用 */
export function useToasts() {
  return { items, dismiss }
}
