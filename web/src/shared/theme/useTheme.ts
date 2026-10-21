import { computed, readonly, ref } from 'vue'

/**
 * 双主题状态 —— 模块级单例，够用，不需要 pinia。
 *
 * 主题落在根元素的 data-theme 属性上，令牌选择器据此生效。
 * 两个入口 HTML 的 head 里都有一段内联脚本做同样的事，保证首帧颜色正确；
 * 这里负责 Vue 侧的状态、切换、持久化与副作用。
 */
export type Theme = 'night' | 'day'

const KEY = 'qqbot.theme'
/** 切换时给页面 320ms 的底色过渡，让「天亮了」有个过程；首帧不加，避免冷启动闪 */
const ANIM_MS = 360

const theme = ref<Theme>('night')
let initialized = false

/** 系统偏好：只有用户没手动选过时才参考 */
function systemTheme(): Theme {
  if (typeof window === 'undefined' || !window.matchMedia) return 'night'
  return window.matchMedia('(prefers-color-scheme: light)').matches ? 'day' : 'night'
}

function stored(): Theme | null {
  try {
    const v = localStorage.getItem(KEY)
    return v === 'day' || v === 'night' ? v : null
  } catch {
    // 隐私模式下 localStorage 会抛异常 —— 主题仍然可用，只是不记忆
    return null
  }
}

/** 浏览器地址栏、状态栏的颜色跟着页面底走，否则手机上会有一条割裂的色带 */
function syncMetaColor(t: Theme) {
  const meta = document.querySelector('meta[name="theme-color"]')
  if (!meta) return
  meta.setAttribute('content', t === 'day' ? '#e9e1cf' : '#101312')
}

function apply(t: Theme, animate: boolean) {
  if (typeof document === 'undefined') return
  const root = document.documentElement
  root.dataset.theme = t
  root.style.colorScheme = t === 'day' ? 'light' : 'dark'
  syncMetaColor(t)
  if (!animate) return
  root.classList.add('theme-anim')
  window.setTimeout(() => root.classList.remove('theme-anim'), ANIM_MS)
}

/**
 * 初始化。可以在 main.ts 里显式调用，也会在首次 useTheme() 时自动执行。
 * 优先读存储，其次读内联脚本已经写好的 data-theme，最后跟随系统。
 */
export function initTheme() {
  if (initialized || typeof window === 'undefined') return
  initialized = true
  const fromDom = document.documentElement.dataset.theme
  const t: Theme = stored()
    ?? (fromDom === 'day' || fromDom === 'night' ? fromDom : systemTheme())
  theme.value = t
  apply(t, false)
}

export function useTheme() {
  initTheme()

  function set(next: Theme) {
    if (next === theme.value) return
    theme.value = next
    apply(next, true)
    try { localStorage.setItem(KEY, next) } catch { /* 存不了就算了 */ }
  }

  function toggle() {
    set(theme.value === 'day' ? 'night' : 'day')
  }

  return {
    theme: readonly(theme),
    isDay: computed(() => theme.value === 'day'),
    set,
    toggle,
  }
}
