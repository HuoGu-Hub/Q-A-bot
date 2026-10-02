/**
 * 受限的行内标记渲染。
 *
 * <p><b>解决什么问题</b>：公开站的固定文案改成可编辑之后用的是**纯文本**，
 * 于是「资料从哪来」那段里的三个链接（Wiki 原站、CC 协议页、Keen Games）、
 * 页脚的署名链接、还有几处加粗都变成了死文字。但直接放开 HTML 是不行的 ——
 * 文案存在数据库里、由后台编辑，等于把 XSS 的口子交给了任何能进后台的人。
 *
 * <p><b>做法</b>：先**整体转义**，再把两种标记换成固定标签。
 * 顺序不能反 —— 先替换后转义会把刚生成的标签也转义掉；而先转义的话，
 * 用户输入里的 `<script>` 已经变成 `&lt;script&gt;`，后面无论怎么替换都不可能
 * 重新拼出标签来。这是这个函数安全性的全部依据。
 *
 * <p><b>支持的两种标记</b>：
 * <pre>
 *   [文字](https://…)   → 链接（只允许 http/https，其它协议原样显示为文本）
 *   **文字**            → 加粗
 * </pre>
 *
 * <p>刻意只支持这两种。每多支持一种就多一处要论证安全性的地方，而这个功能的
 * 全部价值只是"让那几行文案里的链接能点"。
 */

/** 只放行 http/https —— 挡掉 javascript:、data: 这类会被当成脚本执行的协议 */
function safeUrl(raw: string): string | null {
  const url = raw.trim()
  // 相对路径也允许：站内互链不该被迫写全域名
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

/**
 * 把带标记的文案渲染成可安全 `v-html` 的字符串。
 *
 * @param text 原始文案（可能含标记，也可能就是纯文本）
 */
export function renderInline(text: string | null | undefined): string {
  if (!text) {
    return ''
  }
  // ① 先整体转义 —— 之后文本里不再有任何可执行的标签
  let html = escapeHtml(text)

  // ② 链接：[文字](url)。文字与 url 此时都已被转义，只需校验协议
  html = html.replace(/\[([^\]]+)\]\(([^)\s]+)\)/g, (whole, label: string, url: string) => {
    // 转义过的东西要还原回来再判断（&amp; 之类不应影响协议判断）
    const raw = url.replace(/&amp;/g, '&').replace(/&#39;/g, "'").replace(/&quot;/g, '"')
    const safe = safeUrl(raw)
    if (!safe) {
      return whole // 协议不合法就原样显示，不生成链接
    }
    const href = escapeHtml(safe)
    return `<a href="${href}" target="_blank" rel="noopener noreferrer">${label}</a>`
  })

  // ③ 加粗：**文字**（内容里不可能再有标签，因为已经转义过）
  html = html.replace(/\*\*([^*]+)\*\*/g, '<strong>$1</strong>')

  // ④ 换行按原样保留（文案里有换行时要能显示出来）
  return html
}

/** 管理端用：告诉编辑者支持哪两种标记 */
export const INLINE_MARKUP_HINT = '支持两种标记：[文字](链接) 与 **加粗**'
