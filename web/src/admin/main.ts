import { createApp } from 'vue'
import { router } from './router'
import App from './App.vue'
import { initTheme } from '@shared/theme/useTheme'

import '../theme/base.css'
import '../theme/texture.css'

initTheme()

createApp(App).use(router).mount('#app')
