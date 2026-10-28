<script setup lang="ts">
/**
 * 账号管理。
 *
 * <p>这一页回答三个问题：谁能登录、各自能做什么、最近谁改了什么。
 * 后端有三条硬规则会在这里体现出来：不能删自己、不能删/停用最后一个管理员、
 * 密码至少 8 位。前端只是把错误原样显示，不重复实现规则。
 */
import { computed, onMounted, ref } from 'vue'
import { adminApi, describeError } from '@shared/api/client'
import { ADMIN_ROLES, useAuth, type AdminRoleId } from '../useAuth'
import AdminPage from '@shared/ui/AdminPage.vue'
import Panel from '@shared/ui/Panel.vue'
import Input from '@shared/ui/Input.vue'
import Select from '@shared/ui/Select.vue'
import Button from '@shared/ui/Button.vue'
import Tag from '@shared/ui/Tag.vue'
import Notice from '@shared/ui/Notice.vue'
import Modal from '@shared/ui/Modal.vue'

interface AccountRow {
  id: number
  username: string
  displayName: string
  role: AdminRoleId
  roleLabel: string
  enabled: boolean
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
const error = ref('')
const okMsg = ref('')

const form = ref({ username: '', displayName: '', password: '', role: 'viewer' as AdminRoleId })
const creating = ref(false)

const resetTarget = ref<AccountRow | null>(null)
const resetPassword = ref('')
const resetting = ref(false)

const roleOptions = ADMIN_ROLES.map(r => ({ value: r.value, label: r.label }))

const meId = computed(() => auth.me.value?.id ?? -1)
const adminCount = computed(() => rows.value.filter(r => r.role === 'admin' && r.enabled).length)

function flash(msg: string) {
  okMsg.value = msg
  window.setTimeout(() => { if (okMsg.value === msg) okMsg.value = '' }, 1800)
}

async function load() {
  loading.value = true
  error.value = ''
  try {
    rows.value = await adminApi.get<AccountRow[]>('/users')
    audit.value = await adminApi.get<AuditRow[]>('/users/audit?limit=80')
  } catch (e) {
    error.value = describeError(e)
  } finally {
    loading.value = false
  }
}

async function create() {
  if (creating.value) return
  creating.value = true
  error.value = ''
  try {
    await adminApi.post('/users', {
      username: form.value.username.trim(),
      displayName: form.value.displayName.trim() || null,
      password: form.value.password,
      role: form.value.role,
    })
    form.value = { username: '', displayName: '', password: '', role: 'viewer' }
    flash('已创建账号')
    await load()
  } catch (e) {
    error.value = describeError(e)
  } finally {
    creating.value = false
  }
}

async function setRole(row: AccountRow, role: AdminRoleId) {
  if (role === row.role) return
  error.value = ''
  try {
    await adminApi.put('/users/' + row.id, { displayName: row.displayName, role, enabled: row.enabled })
    flash('已更新角色')
    await load()
  } catch (e) {
    error.value = describeError(e)
    await load()
  }
}

async function toggle(row: AccountRow) {
  error.value = ''
  try {
    await adminApi.put('/users/' + row.id, {
      displayName: row.displayName,
      role: row.role,
      enabled: !row.enabled,
    })
    flash(row.enabled ? '已停用' : '已启用')
    await load()
  } catch (e) {
    error.value = describeError(e)
    await load()
  }
}

function openReset(row: AccountRow) {
  resetTarget.value = row
  resetPassword.value = ''
}

async function doReset() {
  const target = resetTarget.value
  if (!target || resetting.value) return
  resetting.value = true
  error.value = ''
  try {
    await adminApi.post('/users/' + target.id + '/password', { password: resetPassword.value })
    resetTarget.value = null
    flash('密码已重置')
    await load()
  } catch (e) {
    error.value = describeError(e)
  } finally {
    resetting.value = false
  }
}

async function remove(row: AccountRow) {
  if (!window.confirm('确定删除账号「' + row.username + '」？这一步不可撤销。')) return
  error.value = ''
  try {
    await adminApi.del('/users/' + row.id)
    flash('已删除')
    await load()
  } catch (e) {
    error.value = describeError(e)
  }
}

onMounted(load)
</script>

<template>
  <AdminPage width="wide">
    <template #actions>
      <Button size="sm" :disabled="loading" @click="load">{{ loading ? '刷新中…' : '刷新' }}</Button>
    </template>

    <Notice v-if="error" tone="error">{{ error }}</Notice>
    <Notice v-if="okMsg" tone="ok">{{ okMsg }}</Notice>

    <Panel title="新建账号" hint="账号密码由你设定，发给对方后请提醒他自己改">
      <form class="create" @submit.prevent="create">
        <div class="field">
          <label for="nu">账号</label>
          <Input id="nu" v-model="form.username" placeholder="字母数字下划线，3–32 位" autocomplete="off" />
        </div>
        <div class="field">
          <label for="nd">昵称</label>
          <Input id="nd" v-model="form.displayName" placeholder="可选，显示在顶端" autocomplete="off" />
        </div>
        <div class="field">
          <label for="np">初始密码</label>
          <Input id="np" v-model="form.password" type="password" placeholder="至少 8 位" autocomplete="new-password" />
        </div>
        <div class="field">
          <label for="nr">角色</label>
          <Select id="nr" :model-value="form.role" :options="roleOptions" aria-label="角色"
                  @update:model-value="v => form.role = v as AdminRoleId" />
        </div>
        <Button variant="primary" type="submit" :disabled="creating">
          {{ creating ? '创建中…' : '创建' }}
        </Button>
      </form>
      <p class="hint">
        只读：能看不能改。编辑：能改知识库、命令、文案、地图与公告。管理：全部权限，含这一页。
      </p>
    </Panel>

    <Panel title="账号" :count="rows.length">
      <p v-if="adminCount <= 1" class="hint warn-text">
        当前只有 {{ adminCount }} 个可用管理员。再加一个之前，现有管理员既不能删除也不能降级 —— 这是有意的保护。
      </p>
      <div class="table-wrap">
        <table class="tbl">
          <thead>
            <tr>
              <th>账号</th>
              <th>昵称</th>
              <th>角色</th>
              <th>状态</th>
              <th>最后登录</th>
              <th class="right">操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="r in rows" :key="r.id" :class="{ dim: !r.enabled }">
              <td class="mono">
                {{ r.username }}
                <Tag v-if="r.id === meId" tone="flame">我</Tag>
              </td>
              <td>{{ r.displayName }}</td>
              <td>
                <Select :model-value="r.role" :options="roleOptions" :aria-label="r.username + ' 的角色'"
                        @update:model-value="v => setRole(r, v as AdminRoleId)" />
              </td>
              <td>
                <Tag :tone="r.enabled ? 'good' : 'neutral'">{{ r.enabled ? '启用' : '停用' }}</Tag>
              </td>
              <td class="mono faint">{{ r.lastLoginAt ?? '—' }}</td>
              <td class="right actions">
                <Button size="sm" @click="openReset(r)">重置密码</Button>
                <Button size="sm" @click="toggle(r)">{{ r.enabled ? '停用' : '启用' }}</Button>
                <Button size="sm" variant="danger" :disabled="r.id === meId" @click="remove(r)">删除</Button>
              </td>
            </tr>
            <tr v-if="!rows.length">
              <td colspan="6" class="faint">还没有账号</td>
            </tr>
          </tbody>
        </table>
      </div>
    </Panel>

    <Panel title="最近操作" :count="audit.length" hint="只记写操作与登录，GET 不记">
      <div class="table-wrap">
        <table class="tbl">
          <thead>
            <tr><th>时间</th><th>账号</th><th>动作</th><th>方法</th><th>路径</th><th>详情</th></tr>
          </thead>
          <tbody>
            <tr v-for="a in audit" :key="a.id">
              <td class="mono faint">{{ a.ts }}</td>
              <td class="mono">{{ a.username || '—' }}</td>
              <td>{{ a.action }}</td>
              <td class="mono">{{ a.method }}</td>
              <td class="mono path">{{ a.path }}</td>
              <td class="faint">{{ a.detail }}</td>
            </tr>
            <tr v-if="!audit.length">
              <td colspan="6" class="faint">还没有记录</td>
            </tr>
          </tbody>
        </table>
      </div>
    </Panel>

    <Modal :open="!!resetTarget" :title="'重置密码：' + (resetTarget?.username ?? '')" width="420px"
           @close="resetTarget = null">
      <div class="reset">
        <Input v-model="resetPassword" type="password" placeholder="新的密码，至少 8 位"
               autocomplete="new-password" aria-label="新密码" />
        <p class="hint">重置后原密码立即失效，对方需要重新登录。</p>
        <div class="reset-actions">
          <Button @click="resetTarget = null">取消</Button>
          <Button variant="primary" :disabled="resetting" @click="doReset">
            {{ resetting ? '保存中…' : '确定重置' }}
          </Button>
        </div>
      </div>
    </Modal>
  </AdminPage>
</template>

<style scoped>
.create {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr)) auto;
  gap: var(--sp-3);
  align-items: end;
}
.field { display: flex; flex-direction: column; gap: 6px; min-width: 0; }
.field label { font-size: var(--fs-xs); color: var(--ink-3); }
.hint { margin: var(--sp-3) 0 0; font-size: var(--fs-xs); color: var(--ink-3); }
.warn-text { color: var(--wn, #b45309); }

.table-wrap { overflow-x: auto; }
.tbl { width: 100%; border-collapse: collapse; font-size: var(--fs-sm); }
.tbl th, .tbl td { text-align: left; padding: 9px 10px; border-bottom: 1px solid var(--edge); vertical-align: middle; }
.tbl th { font-weight: var(--fw-semi); color: var(--ink-3); font-size: var(--fs-xs); }
.tbl tr.dim { opacity: .55; }
.tbl .right { text-align: right; }
.tbl .actions { display: flex; gap: var(--sp-1); justify-content: flex-end; flex-wrap: wrap; }
.mono { font-family: ui-monospace, "Cascadia Mono", Consolas, monospace; font-size: var(--fs-xs); }
.path { max-width: 280px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.faint { color: var(--ink-3); }

.reset { display: flex; flex-direction: column; gap: var(--sp-3); }
.reset-actions { display: flex; justify-content: flex-end; gap: var(--sp-2); }

@media (max-width: 900px) {
  .create { grid-template-columns: 1fr; }
}
</style>
