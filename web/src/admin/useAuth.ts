import { computed, ref } from 'vue'
import { adminApi } from '@shared/api/client'

/** 后端 /admin/api/session 返回的身份 */
export interface AdminMe {
  id: number
  username: string
  displayName: string
  role: AdminRoleId
  roleLabel: string
  qq: string
  qqVerified: boolean
}

export interface LoginResult {
  needCode?: boolean
  challengeId?: string
  qq?: string
  user?: AdminMe
}

export type AdminRoleId = 'viewer' | 'editor' | 'admin'

const RANK: Record<AdminRoleId, number> = { viewer: 1, editor: 2, admin: 3 }

/**
 * 会话状态（模块级单例，够用 —— 不需要 pinia）。
 *
 * ⚠️ 这只是**体验层**的判断：真正的安全边界在后端。
 * 前端守卫的作用是"别让用户看到一堆 401 / 403"和"别把管不了的入口摆出来"，
 * 而不是"防止未授权访问"。
 */
const ok = ref(false)
const checked = ref(false)
const me = ref<AdminMe | null>(null)

export function useAuth() {
  return {
    ok,
    checked,
    me,
    /** 当前账号是否达到某一级 */
    can(role: AdminRoleId) {
      const mine = me.value?.role
      return !!mine && RANK[mine] >= RANK[role]
    },
    /** 问一次后端"我还登录着吗"，顺便把身份拿回来 */
    async check() {
      try {
        const r = await adminApi.get<{ user: AdminMe }>('/session')
        me.value = r.user
        ok.value = true
      } catch {
        ok.value = false
        me.value = null
      } finally {
        checked.value = true
      }
    },
    async login(username: string, password: string): Promise<LoginResult> {
      const r = await adminApi.post<LoginResult>('/login', { username, password })
      if (r.needCode) {
        ok.value = false
        me.value = null
        checked.value = true
        return r
      }
      if (r.user) {
        me.value = r.user
        ok.value = true
        checked.value = true
      }
      return r
    },
    async verifyLogin(challengeId: string, code: string, trustDevice: boolean) {
      const r = await adminApi.post<{ user: AdminMe }>('/login/qq-code', {
        challengeId,
        code,
        trustDevice,
      })
      me.value = r.user
      ok.value = true
      checked.value = true
    },
    async logout() {
      try { await adminApi.post('/logout') } catch { /* 忽略 */ }
      ok.value = false
      me.value = null
    },
  }
}

/** 角色显示用的顺序，给下拉选项用 */
export const ADMIN_ROLES: { value: AdminRoleId; label: string }[] = [
  { value: 'viewer', label: '只读' },
  { value: 'editor', label: '编辑' },
  { value: 'admin', label: '管理' },
]

/** 当前登录者是不是管理员（模板里用起来比 auth.can 顺手） */
export function useIsAdmin() {
  return computed(() => useAuth().can('admin'))
}
