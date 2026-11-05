interface SourceMeta {
  label: string
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
