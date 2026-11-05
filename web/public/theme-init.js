(function () {
  try {
    var t = localStorage.getItem('qqbot.theme')
    if (t !== 'day' && t !== 'night') {
      t = window.matchMedia('(prefers-color-scheme: light)').matches ? 'day' : 'night'
    }
    document.documentElement.dataset.theme = t
    document.documentElement.style.colorScheme = t === 'day' ? 'light' : 'dark'
  } catch (e) {
  }
})()
