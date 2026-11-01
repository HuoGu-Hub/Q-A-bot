<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { adminApi, describeError } from '@shared/api/client'
import { toast } from '@shared/ui/toast'
import { ADMIN_ROLES } from '../../useAuth'
import Panel from '@shared/ui/Panel.vue'
import Button from '@shared/ui/Button.vue'
import Notice from '@shared/ui/Notice.vue'

/**
 * 角色 × 区域的勾选矩阵。
 *
 * <p>它只回答"这一级能进哪几块"。**能不能改**是另一条线，由身份级别决定 ——
 * 把「系统」勾给只读，他看到日志，仍然一条也改不动。两件事分开之后，
 * 这套勾选才敢在接待访客时随便调整。
 *
 * <p>「管理 × 账号」固定勾上且不可取消：账号页是改回这张表的唯一入口，
 * 关掉它等于给自己留一把没有备份的锁。
 */
interface AreaRow { id: string; label: string }
interface ScopesResponse {
  areas: AreaRow[]
  scopes: Record<string, string[]>
}

const areas = ref<AreaRow[]>([])
const draft = ref<Record<string, string[]>>({})
const saved = ref<Record<string, string[]>>({})
const loading = ref(false)
const saving = ref(false)
const error = ref('')

/** 管理员必须保留的区域 —— 与后端 AdminArea.mustKeepFor 是同一条规则 */
const LOCKED: Record<string, string> = { admin: 'users' }

const roleList = ADMIN_ROLES

function sortKey(v: Record<string, string[]>) {
  return JSON.stringify(
    roleList.map((r) => [r.value, [...(v[r.value] ?? [])].sort()]),
  )
}

const dirty = computed(() => sortKey(draft.value) !== sortKey(saved.value))

function has(role: string, area: string) {
  return (draft.value[role] ?? []).includes(area)
}

function locked(role: string, area: string) {
  return LOCKED[role] === area
}

function toggle(role: string, area: string) {
  if (locked(role, area)) return
  const cur = new Set(draft.value[role] ?? [])
  if (cur.has(area)) cur.delete(area)
  else cur.add(area)
  draft.value = { ...draft.value, [role]: [...cur] }
}

async function load() {
  loading.value = true
  error.value = ''
  try {
    const r = await adminApi.get<ScopesResponse>('/users/scopes')
    areas.value = r.areas
    draft.value = { ...r.scopes }
    saved.value = { ...r.scopes }
  } catch (e) {
    error.value = describeError(e)
  } finally {
    loading.value = false
  }
}

async function save() {
  if (saving.value || !dirty.value) return
  saving.value = true
  error.value = ''
  try {
    const r = await adminApi.put<ScopesResponse>('/users/scopes', { scopes: draft.value })
    areas.value = r.areas
    draft.value = { ...r.scopes }
    saved.value = { ...r.scopes }
    toast('可访问范围已保存')
  } catch (e) {
    // 留在页面上：保存失败意味着屏幕上的勾选没有生效，不能靠一闪而过的提示
    error.value = describeError(e)
  } finally {
    saving.value = false
  }
}

function reset() {
  draft.value = { ...saved.value }
}

onMounted(load)
</script>

<template>
  <Panel title="每个角色的可访问范围">
    <Notice v-if="error" tone="error">{{ error }}</Notice>

    <p v-if="loading" class="hint">读取中…</p>

    <template v-else>
      <div class="matrix-wrap">
      <table class="matrix">
        <thead>
          <tr>
            <th scope="col" class="area-col">区域</th>
            <th v-for="r in roleList" :key="r.value" scope="col">{{ r.label }}</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="a in areas" :key="a.id">
            <th scope="row">{{ a.label }}</th>
            <td v-for="r in roleList" :key="r.value">
              <label class="cell" :class="{ locked: locked(r.value, a.id) }">
                <input
                  type="checkbox"
                  :checked="has(r.value, a.id)"
                  :disabled="locked(r.value, a.id)"
                  :aria-label="a.label + ' · ' + r.label"
                  @change="toggle(r.value, a.id)"
                />
                <span v-if="locked(r.value, a.id)" class="lock">必须保留</span>
              </label>
            </td>
          </tr>
        </tbody>
      </table>
      </div>

      <div class="actions">
        <span v-if="dirty" class="dirty">有未保存的改动</span>
        <Button size="sm" :disabled="!dirty || saving" @click="reset">撤销</Button>
        <Button size="sm" variant="primary" :disabled="!dirty || saving" @click="save">
          {{ saving ? '保存中…' : '保存' }}
        </Button>
      </div>
    </template>
  </Panel>
</template>

<style scoped>
/* 定宽而不是铺满：1400px 宽的页面上，7×3 的勾选矩阵摊开会像一张没填完的表，
   复选框彼此隔着一百多像素，读起来对不上行。640px 是刚好一行扫完的宽度。 */
/* 窄屏兜底：矩阵的最小内容宽度比 390px 的可用宽度大一点，
   让它自己横滑，而不是把整页顶出横向滚动条。 */
.matrix-wrap { overflow-x: auto; }

.matrix {
  width: 100%;
  max-width: 640px;
  table-layout: fixed;
  border-collapse: separate;
  border-spacing: 0;
  font-size: var(--fs-sm);
}
.matrix th:first-child { width: 46%; }
.matrix th,
.matrix td {
  padding: var(--sp-2) var(--sp-3);
  border-bottom: 1px solid var(--line);
  text-align: center;
}
.matrix thead th {
  font-size: var(--fs-meta);
  font-weight: var(--fw-medium);
  color: var(--ink-dim);
  letter-spacing: var(--tracking-label);
  border-bottom: 1px solid var(--line-strong);
}
.matrix tbody th {
  text-align: left;
  font-weight: var(--fw-normal);
  color: var(--ink);
  white-space: nowrap;
}
.matrix thead th.area-col { text-align: left; }

.cell {
  display: inline-flex;
  align-items: center;
  gap: var(--sp-2);
  cursor: pointer;
}
.cell.locked { cursor: default; color: var(--ink-faint); }
.cell input { width: 15px; height: 15px; accent-color: var(--flame); cursor: inherit; }
.cell input:focus-visible { outline: 2px solid var(--flame); outline-offset: 2px; }

.lock { font-size: var(--fs-meta); color: var(--ink-faint); white-space: nowrap; }

@media (max-width: 760px) {
  .matrix th, .matrix td { padding: var(--sp-2); }
  .lock { display: none; }
}

.actions {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: var(--sp-3);
  margin-top: var(--sp-4);
}
.dirty { margin-right: auto; font-size: var(--fs-meta); color: var(--ink-dim); }
</style>
