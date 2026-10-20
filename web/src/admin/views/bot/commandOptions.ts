/**
 * 指令与板块共用的下拉选项。
 *
 * <p>抽出来的原因很直接：同一个「谁能用」在指令编辑器、板块编辑器、列表标签里
 * 出现三次。三处各写一份选项，迟早会出现「编辑器里有、列表里没有」的漂移。
 */

export const KIND_OPTIONS: Array<{ value: string; label: string }> = [
  { value: 'template', label: '话术（直接回复，不调模型）' },
  { value: 'agent', label: '智能问答（调模型，计入预算）' },
  { value: 'action', label: '动作（写库 / 查库，不调模型）' },
]

export const ACCESS_OPTIONS: Array<{ value: string; label: string }> = [
  { value: 'all', label: '全体' },
  { value: 'admin', label: '管理员' },
  { value: 'owner', label: '群主' },
  { value: 'whitelist', label: '白名单（只对名单里的人）' },
  { value: 'blacklist', label: '黑名单（名单里的人不能用）' },
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
]

export const MODE_OPTIONS: Array<{ value: string; label: string }> = [
  { value: 'kb', label: '走知识库（默认）' },
  { value: 'none', label: '不用知识库（纯模型回答）' },
]

export const DATA_SCOPES: Array<{ value: string; label: string }> = [
  { value: 'GROUP', label: '只看本群' },
  { value: 'SOME', label: '只在这几个群之间' },
  { value: 'ALL', label: '所有群' },
]

export const SCOPE_LABEL: Record<string, string> = {
  GROUP: '本群', SOME: '指定群', ALL: '全部群',
}

export const DELIVERY_OPTIONS: Array<{ value: string; label: string }> = [
  { value: 'direct', label: '立即展示（收下就出现）' },
  { value: 'scheduled', label: '到点发送（收下后等时间）' },
]
