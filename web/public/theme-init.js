/**
 * 首帧主题。必须作为阻塞脚本放在任何样式之前：
 * CSS 由 JS 模块引入，等它加载完再切主题，用户会先看到一帧夜间再翻成日间。
 *
 * 独立文件是为了让 CSP 能用 script-src 'self'，不再依赖内联脚本。
 */
(function () {
  try {
    var t = localStorage.getItem('qqbot.theme')
    if (t !== 'day' && t !== 'night') {
      t = window.matchMedia('(prefers-color-scheme: light)').matches ? 'day' : 'night'
    }
    document.documentElement.dataset.theme = t
    document.documentElement.style.colorScheme = t === 'day' ? 'light' : 'dark'
  } catch (e) {
    /* 隐私模式下读不到存储，交给 CSS 默认的夜间 */
  }
})()
