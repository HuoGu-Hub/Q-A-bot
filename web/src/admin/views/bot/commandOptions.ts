/**
 * 指令与板块共用的下拉选项。
 *
 * <p>抽出来的原因很直接：同一个「谁能用」在指令编辑器、板块编辑器、列表标签里
 * 出现三次。三处各写一份选项，迟早会出现「编辑器里有、列表里没有」的漂移。
 */

export const KIND_OPTIONS: Array<{ value: string; label: string }> = [
  { value: 'template', label: '话术' },
  { value: 'agent', label: '问答' },
  { value: 'action', label: '动作' },
]

export const ACCESS_OPTIONS: Array<{ value: string; label: string }> = [
  { value: 'all', label: '全体' },
  { value: 'admin', label: '管理员' },
  { value: 'owner', label: '群主' },
  { value: 'whitelist', label: '白名单' },
  { value: 'blacklist', label: '黑名单' },
]

export const ACCESS_LABEL: Record<string, string> = {
  all: '全体', admin: '管理员', owner: '群主', whitelist: '白名单', blacklist: '黑名单',
}

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
