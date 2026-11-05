<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { adminApi, describeError } from '@shared/api/client'
import { ADMIN_ROLES, useAuth, type AdminRoleId } from '../useAuth'
import AdminPage from '@shared/ui/AdminPage.vue'
import Panel from '@shared/ui/Panel.vue'
import Input from '@shared/ui/Input.vue'
import Button from '@shared/ui/Button.vue'
import Tag from '@shared/ui/Tag.vue'
import Notice from '@shared/ui/Notice.vue'
import Modal from '@shared/ui/Modal.vue'
import Field from '@shared/ui/Field.vue'
import Icon from '@shared/ui/Icon.vue'
import RoleScopePanel from './users/RoleScopePanel.vue'
import { toast } from '@shared/ui/toast'

interface AccountRow {
  id: number
  username: string
  displayName: string
  role: AdminRoleId
  roleLabel: string
  enabled: boolean
  qq: string
  qqVerified: boolean
  createdAt: string
  lastLoginAt: string | null
}

interface AuditRow {
  id: number
  ts: string
  username: string
  action: string
  method: string
  path: string
  detail: string
}

const auth = useAuth()
const rows = ref<AccountRow[]>([])
const audit = ref<AuditRow[]>([])
const loading = ref(false)
const loadError = ref('')

const meId = computed(() => auth.me.value?.id ?? -1)
const adminCount = computed(() => rows.value.filter(r => r.role === 'admin' && r.enabled).length)

const createOpen = ref(false)
const form = ref({ username: '', displayName: '', password: '', role: 'viewer' as AdminRoleId, qq: '' })
const creating = ref(false)
const createStep = ref<'form' | 'code'>('form')
const createChallenge = ref('')
const createAdminQq = ref('')
const createCode = ref('')

function openCreate() {
  form.value = { username: '', displayName: '', password: '', role: 'viewer', qq: '' }
  createStep.value = 'form'
  createChallenge.value = ''
  createCode.value = ''
  createOpen.value = true
}

const editTarget = ref<AccountRow | null>(null)
const roleDraft = ref<AdminRoleId>('viewer')
const savingRole = ref(false)
const newPassword = ref('')
const passwordError = ref('')
const savingPassword = ref(false)
const confirmingDelete = ref(false)
const working = ref(false)

function openEdit(r: AccountRow) {
  editTarget.value = r
  roleDraft.value = r.role
  newPassword.value = ''
  passwordError.value = ''
  confirmingDelete.value = false
}

function closeEdit() {
  editTarget.value = null
  confirmingDelete.value = false
}

const isSelf = computed(() => editTarget.value?.id === meId.value)

const deleteBlocked = computed(() => {
  if (isSelf.value) return '不能删除自己'
  if (isLastAdmin.value) return '最后一个管理员不能删除'
  return ''
})
const isLastAdmin = computed(() =>
  !!editTarget.value
  && editTarget.value.role === 'admin'
  && editTarget.value.enabled
  && adminCount.value <= 1)

function qqLabel(r: AccountRow) {
  if (r.qq) return 'QQ ' + r.qq + (r.qqVerified ? '' : ' · 待验证')
  return r.role === 'viewer' ? '免绑 QQ' : '未绑定 QQ'
}

function roleName(r: AdminRoleId) {
  return ADMIN_ROLES.find(x => x.value === r)?.label ?? r
}

function roleLocked(r: AdminRoleId) {
  return isLastAdmin.value && r !== 'admin'
}

async function load() {
  loading.value = true
  loadError.value = ''
  try {
    rows.value = await adminApi.get<AccountRow[]>('/users')
    audit.value = await adminApi.get<AuditRow[]>('/users/audit?limit=80')
    if (editTarget.value) {
      editTarget.value = rows.value.find((r) => r.id === editTarget.value?.id) ?? null
    }
  } catch (e) {
    loadError.value = describeError(e)
  } finally {
    loading.value = false
  }
}

async function requestCreate() {
  if (creating.value) return
  creating.value = true
  try {
    const r = await adminApi.post<{ challengeId: string; adminQq: string }>('/users/request', {
      username: form.value.username.trim(),
      displayName: form.value.displayName.trim() || null,
      password: form.value.password,
      role: form.value.role,
      qq: form.value.qq.trim(),
    })
    createChallenge.value = r.challengeId
    createAdminQq.value = r.adminQq
    createStep.value = 'code'
  } catch (e) {
    toast(describeError(e), 'error', 6000)
  } finally {
    creating.value = false
  }
}

async function confirmCreate() {
  if (creating.value) return
  creating.value = true
  try {
    await adminApi.post('/users/confirm', {
      challengeId: createChallenge.value,
      code: createCode.value.trim(),
    })
    createOpen.value = false
    toast('已创建账号 ' + form.value.username.trim())
    await load()
  } catch (e) {
    toast(describeError(e), 'error', 6000)
  } finally {
    creating.value = false
  }
}

async function saveRole() {
  const t = editTarget.value
  if (!t || savingRole.value) return
  savingRole.value = true
  try {
    await adminApi.put('/users/' + t.id, { displayName: t.displayName, role: roleDraft.value, enabled: t.enabled })
    toast('已把 ' + t.username + ' 改为' + roleName(roleDraft.value))
    await load()
  } catch (e) {
    toast(describeError(e), 'error', 6000)
    await load()
  } finally {
    savingRole.value = false
  }
}

async function toggleEnabled() {
  const t = editTarget.value
  if (!t || working.value) return
  working.value = true
  try {
    await adminApi.put('/users/' + t.id, {
      displayName: t.displayName,
      role: t.role,
      enabled: !t.enabled,
    })
    toast(t.enabled ? '已停用 ' + t.username : '已启用 ' + t.username)
    await load()
  } catch (e) {
    toast(describeError(e), 'error', 6000)
    await load()
  } finally {
    working.value = false
  }
}

async function saveNewPassword() {
  const t = editTarget.value
  if (!t || savingPassword.value) return
  if (newPassword.value.length < 12) {
    passwordError.value = '新密码至少 12 位'
    return
  }
  passwordError.value = ''
  savingPassword.value = true
  try {
    await adminApi.post('/users/' + t.id + '/password', { password: newPassword.value })
    newPassword.value = ''
    toast('新密码已保存，请把它发给 ' + t.username)
  } catch (e) {
    passwordError.value = describeError(e)
  } finally {
    savingPassword.value = false
  }
}

async function remove() {
  const t = editTarget.value
  if (!t || working.value) return
  working.value = true
  try {
    await adminApi.del('/users/' + t.id)
    closeEdit()
    toast('已删除 ' + t.username)
    await load()
  } catch (e) {
    toast(describeError(e), 'error', 6000)
  } finally {
    working.value = false
  }
}

const pad = (n: number) => String(n).padStart(2, '0')
const dayKey = (d: Date) => d.getFullYear() + '-' + pad(d.getMonth() + 1) + '-' + pad(d.getDate())

function parseTs(ts: string | null): Date | null {
  if (!ts) return null
  const d = new Date(ts.includes('T') ? ts : ts.replace(' ', 'T'))
  return Number.isNaN(d.getTime()) ? null : d
}

function rel(ts: string | null): string {
  const d = parseTs(ts)
  if (!d) return '从未登录'
  const min = Math.floor((Date.now() - d.getTime()) / 60000)
  if (min < 1) return '刚刚'
  if (min < 60) return min + ' 分钟前'
  const h = Math.floor(min / 60)
  if (h < 24) return h + ' 小时前'
  const days = Math.floor(h / 24)
  if (days < 30) return days + ' 天前'
  return d.getFullYear() + '-' + pad(d.getMonth() + 1) + '-' + pad(d.getDate())
}

const auditGroups = computed(() => {
  const out: { day: string; label: string; items: AuditRow[] }[] = []
  const today = new Date()
  const yest = new Date(today.getTime() - 86400000)
  for (const a of audit.value) {
    const day = String(a.ts ?? '').slice(0, 10)
    let g = out.find((x) => x.day === day)
    if (!g) {
      const label = day === dayKey(today) ? '今天' : day === dayKey(yest) ? '昨天' : day.slice(5)
      g = { day, label, items: [] }
      out.push(g)
    }
    g.items.push(a)
  }
  return out
})

function hhmm(ts: string) {
  return String(ts ?? '').slice(11, 16) || '—'
}

onMounted(load)
</script>

<template>
  <AdminPage width="wide" title="账号" :count="rows.length">
    <template #actions>
      <Button size="sm" :disabled="loading" @click="load">{{ loading ? '刷新中…' : '刷新' }}</Button>
      <Button size="sm" variant="primary" @click="openCreate">
        <Icon name="plus" :size="14" />新增账号
      </Button>
    </template>

    <Notice v-if="loadError" tone="error">{{ loadError }}</Notice>

    <Panel>
      <p v-if="loading && !rows.length" class="hint">读取中…</p>

      <ul v-else class="people">
        <li v-for="r in rows" :key="r.id" class="person" :class="{ out: !r.enabled }">
          <span class="ember" :class="{ lit: r.enabled }" aria-hidden="true" />
          <span class="who">
            <span class="name mono">
              {{ r.username }}
              <Tag v-if="r.id === meId" tone="flame">我</Tag>
            </span>
            <span class="display" :class="{ unfilled: !r.displayName }">{{ r.displayName || '未设昵称' }}</span>
            <span class="qq">{{ qqLabel(r) }}</span>
            <span class="sr-only">{{ r.enabled ? '启用中' : '已停用' }}</span>
          </span>
          <span class="rank">{{ roleName(r.role) }}</span>
          <span class="seen num" :title="r.lastLoginAt ?? '从未登录'">{{ rel(r.lastLoginAt) }}</span>
          <Button size="sm" class="manage" :aria-label="'管理账号 ' + r.username" @click="openEdit(r)">管理</Button>
        </li>
        <li v-if="!rows.length && !loadError" class="empty">还没有账号。</li>
      </ul>
    </Panel>

    <RoleScopePanel />

    <Panel title="最近改动" :count="audit.length">
      <div v-if="auditGroups.length" class="trail">
        <section v-for="g in auditGroups" :key="g.day" class="day">
          <h3 class="day-label">{{ g.label }}</h3>
          <ul class="events">
            <li v-for="a in g.items" :key="a.id" class="event" :title="a.method + ' ' + a.path">
              <span class="t num">{{ hhmm(a.ts) }}</span>
              <span class="actor mono">{{ a.username || '—' }}</span>
              <span class="what">
                {{ a.action }}<span v-if="a.detail" class="detail"> · {{ a.detail }}</span>
              </span>
              <span class="path mono">{{ a.path }}</span>
            </li>
          </ul>
        </section>
      </div>
      <p v-else class="empty">还没有记录。第一次登录之后，这里会留下痕迹。</p>
    </Panel>

    <Modal :open="createOpen" title="新增账号" width="560px" @close="createOpen = false">
      <form v-if="createStep === 'form'" class="form" @submit.prevent="requestCreate">
        <Field label="账号" hint="字母、数字、下划线，3–32 位" control-width="240px">
          <Input v-model="form.username" placeholder="例如 xiaoxue" autocomplete="off" name="username" />
        </Field>
        <Field label="昵称" control-width="240px">
          <Input v-model="form.displayName" placeholder="例如 小雪" autocomplete="off" name="displayName" />
        </Field>
        <Field label="QQ 号" hint="编辑与管理必填；只读可留空" control-width="240px">
          <Input v-model="form.qq" placeholder="例如 10001" inputmode="numeric" autocomplete="off" name="qq" />
        </Field>
        <Field label="初始密码" hint="至少 12 位" control-width="240px">
          <Input v-model="form.password" type="password" placeholder="至少 12 位" autocomplete="new-password" name="password" />
        </Field>

        <fieldset class="roles">
          <legend class="legend">身份</legend>
          <div class="role-grid">
            <button
              v-for="r in ADMIN_ROLES"
              :key="r.value"
              type="button"
              class="role-card"
              :class="{ on: form.role === r.value }"
              :aria-pressed="form.role === r.value"
              @click="form.role = r.value"
            >
              <span class="role-name">{{ r.label }}</span>
            </button>
          </div>
        </fieldset>

        <div class="form-actions">
          <Button type="button" @click="createOpen = false">取消</Button>
          <Button variant="primary" type="submit" :disabled="creating">
            {{ creating ? '发送中…' : '发送验证码' }}
          </Button>
        </div>
      </form>

      <form v-else class="form" @submit.prevent="confirmCreate">
        <p class="code-hint">验证码已发送到管理员 QQ {{ createAdminQq }}</p>
        <Field label="验证码" control-width="200px">
          <Input v-model="createCode" placeholder="6 位验证码" inputmode="numeric" maxlength="6" autocomplete="one-time-code" name="code" />
        </Field>
        <div class="form-actions">
          <Button type="button" @click="createStep = 'form'; createCode = ''">上一步</Button>
          <Button variant="primary" type="submit" :disabled="creating">
            {{ creating ? '创建中…' : '确认创建' }}
          </Button>
        </div>
      </form>
    </Modal>

    <Modal
      :open="!!editTarget"
      :title="'账号：' + (editTarget?.username ?? '')"
      width="560px"
      @close="closeEdit"
    >
      <div v-if="editTarget" class="form">
        <dl class="facts">
          <div><dt>身份</dt><dd>{{ roleName(editTarget.role) }}</dd></div>
          <div><dt>最后登录</dt><dd>{{ rel(editTarget.lastLoginAt) }}</dd></div>
          <div><dt>创建于</dt><dd>{{ editTarget.createdAt }}</dd></div>
        </dl>

        <p v-if="isLastAdmin" class="guard">
          <Icon name="lock" :size="14" />
          <span>这是最后一个可用管理员，不能降级、停用或删除。先加一个管理员，再回来改它。</span>
        </p>

        <div class="role-save-row">
          <fieldset class="roles">
            <legend class="legend">更改身份</legend>
            <div class="role-grid">
              <button
                v-for="r in ADMIN_ROLES"
                :key="r.value"
                type="button"
                class="role-card"
                :class="{ on: roleDraft === r.value }"
                :aria-pressed="roleDraft === r.value"
                :disabled="roleLocked(r.value)"
                @click="roleDraft = r.value"
              >
                <span class="role-name">{{ r.label }}</span>
              </button>
            </div>
          </fieldset>
          <div class="form-actions role-actions">
            <Button
              variant="primary"
              :disabled="savingRole || roleDraft === editTarget.role"
              @click="saveRole"
            >{{ savingRole ? '保存中…' : '保存身份' }}</Button>
          </div>
        </div>

        <hr class="rule">

        <Field label="设置新密码" hint="至少 12 位；系统没有默认密码" control-width="240px">
          <Input
            v-model="newPassword"
            type="password"
            placeholder="输入新密码"
            autocomplete="new-password"
            aria-label="新的密码"
            @update:model-value="passwordError = ''"
          />
          <span v-if="passwordError" class="field-error" role="alert">{{ passwordError }}</span>
        </Field>
        <div class="form-actions">
          <Button :disabled="savingPassword || !newPassword" @click="saveNewPassword">
            {{ savingPassword ? '保存中…' : '保存新密码' }}
          </Button>
        </div>

        <hr class="rule">

        <div class="zone">
          <div class="zone-text">
            <div class="zone-title">停用或删除</div>
          </div>
          <div class="zone-actions">
            <Button
              size="sm"
              :disabled="working || (isLastAdmin && editTarget.enabled)"
              :title="isLastAdmin && editTarget.enabled ? '最后一个管理员不能停用' : ''"
              @click="toggleEnabled"
            >{{ editTarget.enabled ? '停用账号' : '启用账号' }}</Button>

            <template v-if="!confirmingDelete">
              <Button
                size="sm"
                variant="danger"
                :disabled="isSelf || isLastAdmin"
                :title="deleteBlocked"
                @click="confirmingDelete = true"
              >删除</Button>
            </template>
            <template v-else>
              <span class="confirm-note">删除后无法恢复。</span>
              <Button size="sm" @click="confirmingDelete = false">取消</Button>
              <Button size="sm" variant="danger" :disabled="working" @click="remove">确认删除</Button>
            </template>
          </div>
        </div>
      </div>
    </Modal>
  </AdminPage>
</template>

<style scoped>
.people { list-style: none; margin: 0; padding: 0; }
.person {
  display: grid;
  grid-template-columns: 10px minmax(0, 1fr) auto;
  grid-template-areas:
    'ember who    manage'
    'ember rank   seen';
  gap: 2px var(--sp-3);
  align-items: center;
  padding: var(--sp-3) var(--sp-2);
  border-radius: var(--r-sm);
  transition: background var(--dur-fast) var(--ease);
}
.person + .person { border-top: 1px solid var(--hairline); }
.person:hover { background: var(--surface-hover); }

.ember {
  grid-area: ember;
  align-self: center;
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: var(--ink-4);
}
.ember.lit {
  background: var(--ember);
  box-shadow: 0 0 0 3px var(--ember-veil), 0 0 10px var(--ember-glow);
}

.who { grid-area: who; min-width: 0; display: flex; flex-direction: column; gap: 1px; }
.name {
  display: inline-flex;
  align-items: center;
  gap: var(--sp-2);
  font-size: var(--fs-base);
  color: var(--ink);
  letter-spacing: .02em;
}
.display { font-size: var(--fs-xs); color: var(--ink-3); }
.qq { font-size: var(--fs-xs); color: var(--ink-4); }
.display.unfilled { color: var(--ink-4); }
.person.out .name { color: var(--ink-3); }
.person.out .display { color: var(--ink-4); }

.rank {
  grid-area: rank;
  font-family: var(--font-title);
  font-size: var(--fs-base);
  color: var(--ink-2);
  letter-spacing: .04em;
}
.person.out .rank { color: var(--ink-4); }

.seen {
  grid-area: seen;
  justify-self: end;
  font-size: var(--fs-xs);
  color: var(--ink-3);
  white-space: nowrap;
}
.person.out .seen { color: var(--ink-4); }
.manage { grid-area: manage; align-self: center; }

.empty { margin: 0; padding: var(--sp-6) 0; text-align: center; color: var(--ink-3); font-size: var(--fs-sm); }

.guard {
  display: flex;
  align-items: flex-start;
  gap: var(--sp-2);
  margin: 0 0 var(--sp-3);
  padding: var(--sp-3) var(--sp-3);
  border-left: 3px solid var(--warn);
  border-radius: 0 var(--r-sm) var(--r-sm) 0;
  background: var(--warn-veil);
  color: var(--warn);
  font-size: var(--fs-xs);
  line-height: 1.7;
}
.guard :deep(svg) { flex: none; margin-top: 2px; }

.day + .day { margin-top: var(--sp-4); }
.day-label {
  margin: 0 0 var(--sp-1);
  font-family: var(--font-mono);
  font-size: var(--fs-micro);
  font-weight: var(--fw-normal);
  letter-spacing: var(--tracking-label);
  color: var(--ink-4);
}
.events { list-style: none; margin: 0; padding: 0; }
.event {
  display: grid;
  grid-template-columns: 52px 96px minmax(0, 1fr) minmax(0, 210px);
  gap: var(--sp-3);
  align-items: baseline;
  padding: 9px 0;
  border-top: 1px solid var(--hairline);
  font-size: var(--fs-sm);
}
.event .t { font-size: var(--fs-xs); color: var(--ink-3); }
.actor { color: var(--ink-2); font-size: var(--fs-xs); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.what { color: var(--ink); min-width: 0; }
.detail { color: var(--ink-3); }
.path {
  color: var(--ink-4);
  font-size: var(--fs-micro);
  text-align: right;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.form { display: flex; flex-direction: column; gap: var(--sp-3); }
.code-hint { margin: 0; color: var(--ink-2); font-size: var(--fs-sm); }
.facts {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: var(--sp-3);
  margin: 0;
}
.facts dt { font-size: var(--fs-micro); color: var(--ink-3); letter-spacing: .04em; }
.facts dd { margin: 2px 0 0; font-size: var(--fs-sm); color: var(--ink-2); }

.roles { border: 0; margin: 0; padding: 0; min-width: 0; }
.legend { padding: 0; margin: 0 0 6px; font-size: var(--fs-xs); color: var(--ink-3); }
.role-grid { display: flex; flex-wrap: wrap; gap: 6px; }
.role-card {
  display: inline-flex;
  flex: 0 1 auto;
  min-width: 76px;
  justify-content: center;
  padding: 8px 14px;
  text-align: center;
  border: 1px solid var(--edge);
  border-radius: var(--r-sm);
  background: var(--stone-400);
  color: var(--ink-2);
  cursor: pointer;
  box-shadow: var(--bevel-shelf);
  transition: border-color var(--dur-fast) var(--ease), background var(--dur-fast) var(--ease);
}
.role-card:hover:not(:disabled) { border-color: var(--edge-hover); background: var(--stone-500); }
.role-card.on {
  border-color: var(--ember);
  background: var(--ember-veil);
  color: var(--ink);
}
.role-card:disabled { opacity: .4; cursor: not-allowed; }
.role-card:focus-visible { outline: 2px solid var(--ember); outline-offset: 2px; }
.role-name { font-family: var(--font-title); font-size: var(--fs-base); letter-spacing: .04em; }
.role-card.on .role-name { color: var(--ember-hot); }

.form-actions { display: flex; justify-content: flex-end; gap: var(--sp-2); }
.role-save-row {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: var(--sp-3);
  flex-wrap: wrap;
}
.role-save-row .roles { flex: 1 1 300px; }
.role-actions { flex: 0 0 auto; }
.field-error {
  display: block;
  margin-top: 4px;
  color: var(--blight-lift);
  font-size: var(--fs-xs);
  line-height: 1.5;
}
.form .rule { margin: var(--sp-1) 0; }

.zone { display: flex; align-items: flex-start; justify-content: space-between; gap: var(--sp-4); flex-wrap: wrap; }
.zone-text { min-width: 0; flex: 1 1 260px; }
.zone-title { font-size: var(--fs-base); color: var(--ink); margin-bottom: 2px; }
.zone-text .hint { margin: 0; }
.zone-actions { display: flex; align-items: center; gap: var(--sp-2); flex-wrap: wrap; justify-content: flex-end; }
.confirm-note { font-size: var(--fs-xs); color: var(--blight-lift); }

@media (min-width: 761px) {
  .person {
    grid-template-columns: 12px minmax(0, 320px) 64px 112px minmax(0, 1fr) auto;
    grid-template-areas: 'ember who rank seen . manage';
    gap: var(--sp-3);
    padding: var(--sp-3) var(--sp-2);
  }
  .seen { justify-self: start; }
}

@media (max-width: 760px) {
  .who { flex-direction: row; align-items: baseline; flex-wrap: wrap; gap: var(--sp-2); }
  .display { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
  .role-grid { gap: 4px; }
  .role-card { min-width: 68px; padding: 8px 10px; }
  .role-save-row { align-items: stretch; }
  .role-save-row .roles { flex: 1 1 100%; }
  .role-actions { flex: 1 1 100%; }
  .facts { grid-template-columns: 1fr 1fr; }
  .event { grid-template-columns: 44px 84px minmax(0, 1fr); }
  .path { display: none; }
  .zone-actions { justify-content: flex-start; }
  .form-actions { justify-content: stretch; }
  .form-actions > * { flex: 1; }
}
</style>
