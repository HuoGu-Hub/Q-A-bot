interface GuardMeta {
  label: string
  hint: string
  blocked: boolean
  question: boolean
}

export const GUARD_META: Record<string, GuardMeta> = {
  pass: {
    label: '已作答',
    hint: '通过全部检查并交给了模型',
    blocked: false,
    question: true,
  },
  auto: {
    label: '主动接话',
    hint: '没有点名，但按意图和概率接了一条',
    blocked: false,
    question: false,
  },
  drop: {
    label: '未响应',
    hint: '没 @ 机器人 / 黑名单 / 被 Guard 拦下 / 预算超限',
    blocked: true,
    question: false,
  },
  fixed_reply: {
    label: '固定话术',
    hint: '回了但不调模型：限流提示 / 敏感词 / 纯表情兜底 / 预算超限',
    blocked: true,
    question: false,
  },
  command: {
    label: '指令',
    hint: '命中后台配置的指令（如 /help）—— 有回应，但不是提问',
    blocked: false,
    question: false,
  },
}

export function guardLabel(action: string): string {
  return GUARD_META[action]?.label ?? action
}

