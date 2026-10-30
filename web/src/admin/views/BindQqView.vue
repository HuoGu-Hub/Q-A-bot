<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { adminApi, describeError } from '@shared/api/client'
import { useAuth } from '../useAuth'
import Button from '@shared/ui/Button.vue'
import Input from '@shared/ui/Input.vue'
import Notice from '@shared/ui/Notice.vue'

const auth = useAuth()
const router = useRouter()

const qq = ref('')
const code = ref('')
const challengeId = ref('')
const qqMasked = ref('')
const busy = ref(false)
const error = ref('')
const sent = ref(false)

const presetQq = computed(() => auth.me.value?.qq ?? '')

onMounted(() => {
  if (presetQq.value) qq.value = presetQq.value
})

async function send() {
  if (!qq.value.trim() || busy.value) return
  busy.value = true
  error.value = ''
  try {
    const r = await adminApi.post<{ challengeId: string; qq: string }>('/me/qq/send-code', {
      qq: qq.value.trim(),
    })
    challengeId.value = r.challengeId
    qqMasked.value = r.qq
    sent.value = true
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
    await adminApi.post('/me/qq/verify', {
      challengeId: challengeId.value,
      code: code.value.trim(),
    })
    await auth.check()
    router.replace('/stats')
  } catch (e) {
    error.value = describeError(e)
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <div class="wrap">
    <div class="card">
      <h1>绑定 QQ</h1>
      <p class="lead">
        这个账号还没有绑定 QQ。绑定之前，后台只能打开这一页。
      </p>

      <form v-if="!sent" @submit.prevent="send">
        <Input
          v-model="qq"
          name="qq"
          placeholder="你的 QQ 号"
          inputmode="numeric"
          autocomplete="off"
          aria-label="你的 QQ 号"
        />
        <p class="hint">这个 QQ 需要先添加 bot 为好友，否则收不到验证码。</p>
        <Button variant="primary" type="submit" block :disabled="busy">
          {{ busy ? '发送中…' : '发送验证码' }}
        </Button>
      </form>

      <form v-else @submit.prevent="verify">
        <p class="hint">验证码已发送到 QQ {{ qqMasked }}</p>
        <Input
          v-model="code"
          name="code"
          placeholder="6 位验证码"
          inputmode="numeric"
          maxlength="6"
          autocomplete="one-time-code"
          aria-label="6 位验证码"
        />
        <Button variant="primary" type="submit" block :disabled="busy">
          {{ busy ? '验证中…' : '验证并绑定' }}
        </Button>
        <Button type="button" block :disabled="busy" @click="sent = false; code = ''">
          换个 QQ
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
.card {
  width: min(440px, 100%);
  display: flex;
  flex-direction: column;
  gap: var(--sp-3);
  padding: var(--sp-5);
  background: var(--stone-300);
  border-radius: var(--r-lg);
  box-shadow: var(--bevel-raised), var(--shadow-pop);
}
h1 { margin: 0; font-size: var(--fs-2xl); letter-spacing: var(--tracking-ink); }
.lead { margin: 0; color: var(--ink-2); line-height: 1.6; }
form { display: flex; flex-direction: column; gap: var(--sp-3); }
.hint { margin: 0; color: var(--ink-3); font-size: var(--fs-sm); line-height: 1.5; }
.err-slot { margin-top: var(--sp-2); }
</style>
