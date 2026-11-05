import { ref } from 'vue'

export type ToastTone = 'ok' | 'error' | 'warn' | 'info'

export interface ToastItem {
  id: number
  text: string
  tone: ToastTone
}

const items = ref<ToastItem[]>([])
let seq = 0

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

export function useToasts() {
  return { items, dismiss }
}
