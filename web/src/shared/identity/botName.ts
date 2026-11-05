import { ref } from 'vue'
import { publicApi } from '@shared/api/client'

export const FALLBACK_BOT_NAME = 'Q-A-bot'

export const botName = ref(FALLBACK_BOT_NAME)

export function setBotName(value: string | null | undefined): void {
  const name = value?.trim()
  if (name) botName.value = name
}

let pending: Promise<void> | null = null

export function loadBotName(): Promise<void> {
  if (!pending) {
    pending = publicApi
      .get<{ botName?: string }>('/site')
      .then((r) => setBotName(r.botName))
      .catch(() => {   })
  }
  return pending
}

export function reloadBotName(): Promise<void> {
  pending = null
  return loadBotName()
}
