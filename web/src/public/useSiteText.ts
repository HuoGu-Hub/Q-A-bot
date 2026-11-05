import { ref } from 'vue'
import { publicApi } from '@shared/api/client'
import { botName, setBotName } from '@shared/identity/botName'
import type { Schemas } from '@shared/api/generated'

export type SiteInfo =
  Required<Pick<Schemas['SiteInfo'],
    'groupName' | 'groupNumber' | 'groupDesc' | 'joinHint' | 'botName' | 'org' | 'author'>>
  & Pick<Schemas['SiteInfo'], 'texts'>

export const site = ref<SiteInfo | null>(null)

let pending: Promise<void> | null = null

export function loadSite(): Promise<void> {
  if (!pending) {
    pending = publicApi
      .get<SiteInfo>('/site')
      .then((r) => { site.value = r; setBotName(r.botName) })
      .catch(() => {   })
  }
  return pending
}

export function t(
  key: string,
  fallback: string,
  params: Record<string, string | number> = {},
): string {
  const raw = site.value?.texts?.[key] ?? fallback
  const vars: Record<string, string | number> = {
    year: new Date().getFullYear(),
    bot: botName.value,
    ...params,
  }
  return raw.replace(/\{(\w+)\}/g, (m, name: string) =>
    Object.prototype.hasOwnProperty.call(vars, name) ? String(vars[name]) : m)
}

void loadSite()
