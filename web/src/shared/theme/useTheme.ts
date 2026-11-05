import { computed, readonly, ref } from 'vue'

export type Theme = 'night' | 'day'

const KEY = 'qqbot.theme'
const ANIM_MS = 360

const theme = ref<Theme>('night')
let initialized = false

function systemTheme(): Theme {
  if (typeof window === 'undefined' || !window.matchMedia) return 'night'
  return window.matchMedia('(prefers-color-scheme: light)').matches ? 'day' : 'night'
}

function stored(): Theme | null {
  try {
    const v = localStorage.getItem(KEY)
    return v === 'day' || v === 'night' ? v : null
  } catch {
    return null
  }
}

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
    try { localStorage.setItem(KEY, next) } catch {   }
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
