import { nextTick, onBeforeUnmount, onMounted, ref } from 'vue'

export function useVariableInsert(setters: Record<string, (value: string) => void>, fallbackField = 'reply') {
  let active: HTMLInputElement | HTMLTextAreaElement | null = null
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
