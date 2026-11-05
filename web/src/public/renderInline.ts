function safeUrl(raw: string): string | null {
  const url = raw.trim()
  if (url.startsWith('/') && !url.startsWith('//')) {
    return url.replace(/["'<>]/g, '')
  }
  try {
    const u = new URL(url)
    return u.protocol === 'http:' || u.protocol === 'https:' ? u.toString() : null
  } catch {
    return null
  }
}

function escapeHtml(s: string): string {
  return s
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#39;')
}

export function renderInline(text: string | null | undefined): string {
  if (!text) {
    return ''
  }
  let html = escapeHtml(text)

  html = html.replace(/\[([^\]]+)\]\(([^)\s]+)\)/g, (whole, label: string, url: string) => {
    const raw = url.replace(/&amp;/g, '&').replace(/&#39;/g, "'").replace(/&quot;/g, '"')
    const safe = safeUrl(raw)
    if (!safe) {
      return whole
    }
    const href = escapeHtml(safe)
    return `<a href="${href}" target="_blank" rel="noopener noreferrer">${label}</a>`
  })

  html = html.replace(/\*\*([^*]+)\*\*/g, '<strong>$1</strong>')

  return html
}

export const INLINE_MARKUP_HINT = '支持两种标记：[文字](链接) 与 **加粗**'
