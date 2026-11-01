/**
 * 准入档位清单 —— **服务端下发，前端只消费**。
 *
 * <h2>为什么不再各写一份</h2>
 * 档位是后端的枚举（{@code CommandAccess}）：哪些值合法、中文叫什么、
 * 哪些档位要靠名单判，都是服务端说了算的事。以前前端在三个地方各抄了一份：
 * {@code commandOptions.ts} 六档、{@code PromptEditor} 三档（还漏了
 * 「管理员与白名单」，文案也不一样），再加上两处 {@code needsAcl} 手写的
 * {@code ['whitelist','blacklist','admin_whitelist']} —— 加一档要改四处，
 * 漏一处就是"这个编辑器里选不到"。
 *
 * <p>现在只有一份：{@code GET /admin/api/commands/access-modes}。
 * 加档位只改后端枚举，三处编辑器自动跟上。
 *
 * <h2>为什么用模块级单例</h2>
 * 这份数据是静态词汇、全站一样，没必要每个面板各拉一次。
 * 三个面板共用同一个 Promise：先到的发起请求，后到的直接等它。
 * 拉取失败允许下次重试（列表会退化成原始 code，不会把值写坏 ——
 * {@code Select} 在没有选项时展示当前值本身）。
 */
import { computed, ref } from 'vue'
import { adminApi } from '@shared/api/client'
import type { AccessMode } from '@shared/api/types'

const modes = ref<AccessMode[]>([])
let loading: Promise<void> | null = null

/** 拉一次档位清单；重复调用复用同一个请求 */
export function loadAccessModes(): Promise<void> {
  if (!loading) {
    loading = adminApi.get<{ modes: AccessMode[] }>('/commands/access-modes')
      .then((r) => { modes.value = r.modes ?? [] })
      .catch(() => { loading = null })
  }
  return loading
}

/** 下拉选项；顺序就是后端的声明顺序（从松到紧） */
export const accessOptions = computed(() =>
  modes.value.map((m) => ({ value: m.code, label: m.label })))

/** 档位的中文名；还没拉到或认不出来时退回原始 code，而不是空字符串 */
export function accessLabel(code: string): string {
  return modes.value.find((m) => m.code === code)?.label ?? code
}

/** 这一档要不要露出名单编辑区 */
export function accessUsesAcl(code: string): boolean {
  return modes.value.find((m) => m.code === code)?.usesAcl ?? false
}
