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
