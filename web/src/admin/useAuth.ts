import { ref } from 'vue'
import { adminApi } from '@shared/api/client'

/**
 * 会话状态（模块级单例，够用 —— 不需要 pinia）。
 *
 * ⚠️ 这只是**体验层**的判断：真正的安全边界在后端。
 * 前端守卫的作用是"别让用户看到一堆 401"，而不是"防止未授权访问"。
 */
const ok = ref(false)
const checked = ref(false)

export function useAuth() {
  return {
    ok,
    checked,
    /** 问一次后端"我还登录着吗" */
    async check() {
      try {
        await adminApi.get('/session')
        ok.value = true
      } catch {
        ok.value = false
      } finally {
        checked.value = true
      }
    },
    async login(password: string) {
      await adminApi.post('/login', { password })
      ok.value = true
      checked.value = true
    },
    async logout() {
      try { await adminApi.post('/logout') } catch { /* 忽略 */ }
      ok.value = false
    },
  }
}
