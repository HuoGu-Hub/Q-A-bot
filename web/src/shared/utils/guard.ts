/**
 * guard_action 的中文语义。
 *
 * 后端存的是四个裸字符串（写在 MessageRouter 里，没有枚举类）。
 * 原来后台直接把它们原样显示给管理员 —— 看板上会出现 `pass`、`drop`、
 * `fixed_reply`、`command` 这种英文枚举，看不懂也不统一。
 *
 * ⚠️ 关键区分：`drop` 与 `fixed_reply` **都算被拦截**（与后端 GuardMetrics 一致），
 * 只有 `pass` 是「未被拦截的有效回应」，也只有它算**提问**。
 * 两边口径必须对齐，否则同一屏上「拦截率」和「提问量」会互相打架。
 */
export type GuardAction = 'pass' | 'drop' | 'fixed_reply' | 'command'

interface GuardMeta {
  /** 中文名 */
  label: string
  /** 一句话解释它为什么是这个归类 */
  hint: string
  /** 算不算被拦截 */
  blocked: boolean
  /** 算不算提问 */
  question: boolean
}

export const GUARD_META: Record<string, GuardMeta> = {
  pass: {
    label: '已作答',
    hint: '通过全部检查并交给了模型',
    blocked: false,
    question: true,
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

/** 未知取值原样返回，不要吞掉 —— 后端加了新 action 时得看得见 */
export function guardLabel(action: string): string {
  return GUARD_META[action]?.label ?? action
}

export function guardHint(action: string): string {
  return GUARD_META[action]?.hint ?? ''
}
