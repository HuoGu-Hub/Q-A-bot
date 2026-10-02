/**
 * 检索来源的中文说法。
 *
 * 后端在 `qa_stat.sources` 里存的是 `vector` / `keyword` / `both` / `none` 四个英文词
 * （`KbRetriever` 里按召回路径打的标）。那是给开发者排查用的行话 ——
 * 管理端是给普通管理员看的，必须说人话。
 *
 * ⚠️ 只在**一个地方**定义这套映射：记录页、看板都要用，
 * 分别写一遍迟早会不一致（项目里已经因为"同一个东西多处各写一份"吃过亏）。
 */
export type HitSource = 'vector' | 'keyword' | 'both' | 'none'

interface SourceMeta {
  /** 中文说法 */
  label: string
  /** 一句话解释这次是怎么找到资料的 */
  hint: string
}

const SOURCE_META: Record<string, SourceMeta> = {
  vector: {
    label: '语义检索',
    hint: '把问题变成向量，在资料库里按意思相近找',
  },
  keyword: {
    label: '关键词匹配',
    hint: '问题里出现了资料页的标题或术语表里的词',
  },
  both: {
    label: '两种都命中',
    hint: '语义检索和关键词匹配都找到了同一份资料',
  },
  none: {
    label: '没找到资料',
    hint: '这次没检索到相关内容，回答没有依据',
  },
}

/**
 * 取中文说法。空值和未知值都给「没找到资料」以外的稳妥显示：
 * 空 → 「没找到资料」（未命中就是没来源）；未知 → 原样返回，不吞掉
 * （后端加了新路径时要能看见，而不是被静默显示成"没找到"）。
 */
export function sourceLabel(source: string | null | undefined): string {
  const s = (source ?? '').trim()
  if (!s) {
    return SOURCE_META.none.label
  }
  return SOURCE_META[s]?.label ?? s
}

export function sourceHint(source: string | null | undefined): string {
  const s = (source ?? '').trim()
  return SOURCE_META[s]?.hint ?? ''
}
