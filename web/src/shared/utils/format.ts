export function pct(x: number, digits = 1): string {
  if (!Number.isFinite(x)) return '—'
  return (x * 100).toFixed(digits) + '%'
}

export function num(x: number | null | undefined): string {
  if (x == null || !Number.isFinite(x)) return '—'
  return x.toLocaleString('zh-CN')
}

export function ms(x: number | null | undefined): string {
  if (x == null || !Number.isFinite(x)) return '—'
  if (x < 1000) return `${Math.round(x)} ms`
  return `${(x / 1000).toFixed(1)} s`
}

export function shortTime(iso: string | null | undefined): string {
  if (!iso) return '—'
  const s = String(iso)
  const i = s.indexOf('T')
  if (i < 0) return s
  return s.slice(5, 10) + ' ' + s.slice(11, 16)
}

export function cosine(x: number | null | undefined): string {
  if (x == null || !Number.isFinite(x)) return '—'
  return x.toFixed(3)
}

export function ellipsis(s: string | null | undefined, max = 60): string {
  const t = s ?? ''
  return t.length > max ? t.slice(0, max) + '…' : t
}

export function verdictTone(v: string | null | undefined): 'good' | 'bad' | 'warn' | 'neutral' {
  switch (v) {
    case 'good': return 'good'
    case 'bad':
    case 'hallucination': return 'bad'
    case 'no_source':
    case 'follow_up': return 'warn'
    default: return 'neutral'
  }
}

export const VERDICT_LABEL: Record<string, string> = {
  good: '答对了',
  bad: '答错了',
  no_source: '资料不足',
  hallucination: '疑似编造',
  follow_up: '被追问',
  unknown: '未标注',
}
