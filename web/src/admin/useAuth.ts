import { computed, ref } from 'vue'
import { adminApi } from '@shared/api/client'

export interface AdminMe {
  id: number
  username: string
  displayName: string
  role: AdminRoleId
  roleLabel: string
  qq: string
  qqVerified: boolean
  qqRequired: boolean
  areas: string[]
}

export interface LoginResult {
  needCode?: boolean
  challengeId?: string
  qq?: string
  user?: AdminMe
}

export type AdminRoleId = 'viewer' | 'editor' | 'admin'

const RANK: Record<AdminRoleId, number> = { viewer: 1, editor: 2, admin: 3 }

const ok = ref(false)
const checked = ref(false)
const me = ref<AdminMe | null>(null)

export function useAuth() {
  return {
    ok,
    checked,
    me,
    can(role: AdminRoleId) {
      const mine = me.value?.role
      return !!mine && RANK[mine] >= RANK[role]
    },
    areaOk(area: string) {
      return !!me.value?.areas?.includes(area)
    },
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
      try { await adminApi.post('/logout') } catch {   }
      ok.value = false
      me.value = null
    },
  }
}

export const ADMIN_ROLES: { value: AdminRoleId; label: string }[] = [
  { value: 'viewer', label: '只读' },
  { value: 'editor', label: '编辑' },
  { value: 'admin', label: '管理' },
]

