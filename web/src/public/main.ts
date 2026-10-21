import { createApp } from 'vue'
import { router } from './router'
import App from './App.vue'
import { initTheme } from '@shared/theme/useTheme'

import '../theme/base.css'
import '../theme/texture.css'

// 主题在挂载前初始化：内联脚本已经写好 data-theme，这里只是把同一份状态同步进 Vue
initTheme()

createApp(App).use(router).mount('#app')
