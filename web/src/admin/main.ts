import { createApp } from 'vue'
import { router } from './router'
import App from './App.vue'

import '../theme/base.css'
import '../theme/texture.css'

createApp(App).use(router).mount('#app')
