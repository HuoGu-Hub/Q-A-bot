<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useAuth } from '../useAuth'
import { describeError } from '@shared/api/client'
import Button from '@shared/ui/Button.vue'
import Input from '@shared/ui/Input.vue'
import Notice from '@shared/ui/Notice.vue'
import ThemeToggle from '@shared/ui/ThemeToggle.vue'
import Icon from '@shared/ui/Icon.vue'

const auth = useAuth()
const route = useRoute()
const router = useRouter()

const username = ref('')
const password = ref('')
const code = ref('')
const challengeId = ref('')
const qqMasked = ref('')
const trustDevice = ref(true)
const step = ref<'password' | 'code'>('password')
const busy = ref(false)
const error = ref('')

/**
 * 自动聚焦：只在精确指针（桌面）上做。
 * 手机上会立刻弹出软键盘：视口被顶掉一半、页面自己滚一段、
 * 「登录」按钮跑到键盘底下 —— 用户第一眼看到的是一个被推歪的页面。
 */
const formEl = ref<HTMLFormElement | null>(null)
onMounted(() => {
  if (typeof window === 'undefined') return
  if (window.matchMedia('(pointer: fine)').matches) {
    formEl.value?.querySelector('input')?.focus()
  }
})

function nextRoute() {
  const r = String(route.query.r ?? '/stats')
  return r.startsWith('/') ? r : '/stats'
}

async function submit() {
  if (!username.value.trim() || !password.value || busy.value) return
  busy.value = true
  error.value = ''
  try {
    const r = await auth.login(username.value.trim(), password.value)
    if (r.needCode) {
      challengeId.value = r.challengeId ?? ''
      qqMasked.value = r.qq ?? ''
      step.value = 'code'
      return
    }
    router.replace(auth.me.value?.qqVerified ? nextRoute() : '/bind-qq')
  } catch (e) {
    error.value = describeError(e)
  } finally {
    busy.value = false
  }
}

async function verify() {
  if (!code.value.trim() || !challengeId.value || busy.value) return
  busy.value = true
  error.value = ''
  try {
    await auth.verifyLogin(challengeId.value, code.value.trim(), trustDevice.value)
    router.replace(auth.me.value?.qqVerified ? nextRoute() : '/bind-qq')
  } catch (e) {
    error.value = describeError(e)
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <!--
    登录页故意不用 AdminPage：这里没有顶端导航，也不需要页面标题栏，
    套上页面外壳只会多出一圈没意义的标题与留白。它是一张居中的卡片。
  -->
  <div class="wrap">
    <div class="card">
      <div class="card-top">
        <span class="mark" aria-hidden="true"><Icon name="flame" :size="20" /></span>
        <ThemeToggle />
      </div>
      <h1 class="t">管理员登录</h1>
      <form v-if="step === 'password'" ref="formEl" @submit.prevent="submit">
        <Input
          v-model="username"
          name="username"
          placeholder="账号或 QQ 号"
          autocomplete="username"
          aria-label="账号或 QQ 号"
        />
        <Input
          v-model="password"
          type="password"
          name="password"
          placeholder="密码"
          autocomplete="current-password"
          aria-label="密码"
        />
        <Button variant="primary" type="submit" block :disabled="busy">
          {{ busy ? '登录中…' : '登录' }}
        </Button>
      </form>

      <form v-else @submit.prevent="verify">
        <p class="code-hint">验证码已发送到 QQ {{ qqMasked }}</p>
        <Input
          v-model="code"
          name="code"
          placeholder="6 位验证码"
          autocomplete="one-time-code"
          inputmode="numeric"
          maxlength="6"
          aria-label="6 位验证码"
        />
        <label class="trust">
          <input v-model="trustDevice" type="checkbox" />
          <span>信任此设备 30 天，期间登录不再收验证码</span>
        </label>
        <Button variant="primary" type="submit" block :disabled="busy">
          {{ busy ? '验证中…' : '验证并登录' }}
        </Button>
        <Button type="button" block :disabled="busy" @click="step = 'password'; code = ''">
          返回重输密码
        </Button>
      </form>
      <div v-if="error" class="err-slot">
        <Notice tone="error">{{ error }}</Notice>
      </div>
    </div>
  </div>
</template>

<style scoped>
.wrap {
  min-height: 100vh;
  min-height: 100dvh;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: var(--sp-4);
}
/* 卡片是这一页唯一的实体，所以给它「凸出来的石板」：明度阶梯 + 上下内阴影 */
.card {
  width: min(400px, 100%);
  display: flex;
  flex-direction: column;
  padding: var(--sp-5);
  background: var(--stone-300);
  border-radius: var(--r-lg);
  box-shadow: var(--bevel-raised), var(--shadow-pop);
}
.card-top {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: var(--sp-4);
}
.mark { color: var(--ember); filter: drop-shadow(0 0 8px var(--ember-glow)); }
/* 副标题删掉之后，标题自己把与表单之间的间距带上 */
.t { font-size: var(--fs-2xl); letter-spacing: var(--tracking-ink); margin: 0 0 var(--sp-4); }
form { display: flex; flex-direction: column; gap: var(--sp-3); }
.err-slot { margin-top: var(--sp-3); }
.code-hint { margin: 0; color: var(--ink-2); font-size: var(--fs-sm); }
.trust { display: flex; align-items: flex-start; gap: var(--sp-2); color: var(--ink-2); font-size: var(--fs-sm); cursor: pointer; }
.trust input { margin-top: 2px; accent-color: var(--ember); }

@media (max-width: 760px) {
  .card { padding: var(--sp-4); }
}
</style>
