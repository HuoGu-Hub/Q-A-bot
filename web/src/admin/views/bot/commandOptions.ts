/**
 * 指令与板块共用的下拉选项。
 *
 * <p>抽出来的原因很直接：同一个选项会在编辑器、列表标签里出现多次，
 * 各写一份迟早出现「编辑器里有、列表里没有」的漂移。
 *
 * <p><b>准入档位不在这里</b>：它是后端的枚举（{@code CommandAccess}），
 * 由 {@code GET /admin/api/commands/access-modes} 下发，见 {@code ./accessModes.ts}。
 * 前端只保留"自己决定"的那几份词汇表。
 */

export const KIND_OPTIONS: Array<{ value: string; label: string }> = [
  { value: 'template', label: '话术' },
  { value: 'agent', label: '问答' },
  { value: 'action', label: '动作' },
]

export const PARAM_TYPES: Array<{ value: string; label: string }> = [
  { value: 'text', label: '文本' },
  { value: 'int', label: '整数' },
  { value: 'moment', label: '时间点' },
  { value: 'date', label: '日期' },
  { value: 'enum', label: '枚举' },
  { value: 'repeat', label: '重复周期' },
]

export const MODE_OPTIONS: Array<{ value: string; label: string }> = [
  { value: 'kb', label: '知识库' },
  { value: 'none', label: '纯模型' },
]

export const DATA_SCOPES: Array<{ value: string; label: string }> = [
  { value: 'GROUP', label: '本群' },
  { value: 'SOME', label: '指定群' },
  { value: 'ALL', label: '所有群' },
]

export const SCOPE_LABEL: Record<string, string> = {
  GROUP: '本群', SOME: '指定群', ALL: '全部群',
}

export const DELIVERY_OPTIONS: Array<{ value: string; label: string }> = [
  { value: 'direct', label: '立即展示' },
  { value: 'scheduled', label: '到点发送' },
]
