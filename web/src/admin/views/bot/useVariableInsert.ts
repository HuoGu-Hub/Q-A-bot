import { nextTick, onBeforeUnmount, onMounted, ref } from 'vue'

/**
 * 记录最后一个获得焦点的编辑框，并把变量插入到它的光标位置。
 *
 * 变量按钮用 @mousedown.prevent，点击时焦点仍然留在 textarea/input 上；
 * 这样回复、list 回复、help 回复不需要各自写一套插入逻辑。
 */
export function useVariableInsert(setters: Record<string, (value: string) => void>, fallbackField = 'reply') {
  let active: HTMLInputElement | HTMLTextAreaElement | null = null
  /** 当前编辑区的 data-var-field —— 编辑区可用的变量不一样，父组件据此切换变量面板 */
  const activeField = ref('')

  function onFocus(event: FocusEvent) {
    const target = event.target
    if (target instanceof HTMLInputElement || target instanceof HTMLTextAreaElement) {
      if (target.dataset.varField) {
        active = target
        activeField.value = target.dataset.varField
      }
    }
  }

  onMounted(() => document.addEventListener('focusin', onFocus))
  onBeforeUnmount(() => document.removeEventListener('focusin', onFocus))

  async function insert(token: string) {
    let el = active
    if (!el || !el.isConnected) {
      el = document.querySelector('[data-var-field="' + fallbackField + '"]')
            ?? document.querySelector('[data-var-field]')
    }
    if (!el) return
    const field = el.dataset.varField
    if (!field || !setters[field]) return

    const start = el.selectionStart ?? el.value.length
    const end = el.selectionEnd ?? start
    const value = el.value
    setters[field](value.slice(0, start) + token + value.slice(end))

    await nextTick()
    if (!el.isConnected) return
    el.focus()
    const pos = start + token.length
    el.setSelectionRange(pos, pos)
  }

  return { insert, activeField }
}
