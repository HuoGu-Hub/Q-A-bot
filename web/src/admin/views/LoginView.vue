<script setup lang="ts">
import { ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useAuth } from '../useAuth'
import { describeError } from '@shared/api/client'
import Button from '@shared/ui/Button.vue'
import Input from '@shared/ui/Input.vue'
import Notice from '@shared/ui/Notice.vue'

const auth = useAuth()
const route = useRoute()
const router = useRouter()

const password = ref('')
const busy = ref(false)
const error = ref('')

async function submit() {
  if (!password.value || busy.value) return
  busy.value = true
  error.value = ''
  try {
    await auth.login(password.value)
    // 合并后默认落点是看板（原来是大屏，现在 /screen 会重定向到 /dashboard）
    const r = String(route.query.r ?? '/dashboard')
    router.replace(r.startsWith('/') ? r : '/dashboard')
  } catch (e) {
    error.value = describeError(e)
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <!--
    登录页【故意】不用 AdminPage：这里没有顶端导航，也不需要页面标题栏，
    套上页面外壳只会多出一圈没意义的标题与留白。它是一张居中的卡片。
  -->
  <div class="wrap">
    <div class="card">
      <h1 class="t">管理员登录</h1>
      <p class="muted sub">后台能看到群里的提问记录，因此需要密码</p>
      <form @submit.prevent="submit">
        <!-- Input / Button 自带悬浮反馈（背景 + 边缘同时变），不再自写 .pwd -->
        <Input
          v-model="password"
          type="password"
          placeholder="密码"
          autocomplete="current-password"
          autofocus
        />
        <Button variant="primary" type="submit" :disabled="busy">
          {{ busy ? '登录中…' : '登录' }}
        </Button>
      </form>
      <div v-if="error" class="err-slot">
        <Notice tone="error">{{ error }}</Notice>
      </div>
      <p class="tip faint">
        密码在服务端 <code>.env</code> 的 <code>ADMIN_PASSWORD</code>；
        没配置时后台会整个返回 503。
      </p>
    </div>
  </div>
</template>

<style scoped>
.wrap {
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: var(--sp-4);
}
/* 卡片自己撑起来（--shadow-lift），不再靠描边分层：
   层级给背景（--surface-panel），边界给柔和边缘（--edge-soft） */
.card {
  width: min(380px, 100%);
  display: flex;
  flex-direction: column;
  padding: var(--sp-5);
  background: var(--surface-panel);
  border: 1px solid var(--edge-soft);
  border-radius: var(--r-md);
  box-shadow: var(--shadow-lift);
}
/* 卡片标题按「页面标题」级字号（24px），不另写字号 */
.t { font-size: var(--fs-display); }
.sub { font-size: var(--fs-meta); margin: var(--sp-1) 0 var(--sp-5); }
form { display: flex; flex-direction: column; gap: var(--sp-3); }
.err-slot { margin-top: var(--sp-3); }
.tip { font-size: var(--fs-meta); margin: var(--sp-5) 0 0; line-height: 1.7; }
</style>
