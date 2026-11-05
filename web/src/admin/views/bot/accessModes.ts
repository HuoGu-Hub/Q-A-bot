import { computed, ref } from 'vue'
import { adminApi } from '@shared/api/client'
import type { AccessMode } from '@shared/api/types'

const modes = ref<AccessMode[]>([])
let loading: Promise<void> | null = null

export function loadAccessModes(): Promise<void> {
  if (!loading) {
    loading = adminApi.get<{ modes: AccessMode[] }>('/commands/access-modes')
      .then((r) => { modes.value = r.modes ?? [] })
      .catch(() => { loading = null })
  }
  return loading
}

export const accessOptions = computed(() =>
  modes.value.map((m) => ({ value: m.code, label: m.label })))

export function accessLabel(code: string): string {
  return modes.value.find((m) => m.code === code)?.label ?? code
}

export function accessUsesAcl(code: string): boolean {
  return modes.value.find((m) => m.code === code)?.usesAcl ?? false
}
