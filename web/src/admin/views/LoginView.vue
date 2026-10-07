<script setup lang="ts">
import { onMounted, ref } from 'vue'
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

/**
 * 自动聚焦：**只在精确指针（桌面）上做**。
 *
 * <p>原来是无条件的 autofocus。手机上会立刻弹出软键盘：视口被顶掉一半、
 * 页面自己滚一段、"登录"按钮跑到键盘底下 —— 用户第一眼看到的是一个被推歪的页面。
 * 桌面端只有一个输入框，自动聚焦确实省一次点击，所以留着。
 */
const formEl = ref<HTMLFormElement | null>(null)
onMounted(() => {
  if (typeof window === 'undefined') return
  if (window.matchMedia('(pointer: fine)').matches) {
    formEl.value?.querySelector('input')?.focus()
  }
})

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
      <form ref="formEl" @submit.prevent="submit">
        <!-- Input / Button 自带悬浮反馈（背景 + 边缘同时变），不再自写 .pwd -->
        <Input
          v-model="password"
          type="password"
          name="password"
          placeholder="密码"
          autocomplete="current-password"
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
/* 卡片是这一页唯一的实体，所以给它"凸出来的石板"：明度阶梯 + 上下内阴影。
   上一版同时写了 border 和 box-shadow（两套分层语言混用）。 */
.card {
  width: min(400px, 100%);
  display: flex;
  flex-direction: column;
  padding: var(--sp-5);
  background: var(--stone-300);
  border-radius: var(--r-lg);
  box-shadow: var(--bevel-raised), var(--shadow-pop);
}
.t { font-size: var(--fs-2xl); letter-spacing: var(--tracking-ink); }
.sub { font-size: var(--fs-sm); color: var(--ink-3); margin: var(--sp-2) 0 var(--sp-5); }
form { display: flex; flex-direction: column; gap: var(--sp-3); }
.err-slot { margin-top: var(--sp-3); }
.tip { font-size: var(--fs-meta); margin: var(--sp-5) 0 0; line-height: 1.7; }
</style>
