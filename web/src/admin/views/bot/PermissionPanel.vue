<script setup lang="ts">
/**
 * 「权限」页 —— 只读总览。
 *
 * 权限最怕的不是配错，是「我以为它没开，其实开了」。所以这一页只做一件事：
 * 把<strong>每条命令的两把锁</strong>并排列出来，让开着的跨群一眼能看见。
 *
 * 不做编辑：改权限在「指令」页逐条改，配上名单则在命令的名单里。
 * 这一页存在的意义就是"扫一遍"。
 */
import { computed, onMounted, ref } from 'vue'
import { adminApi, describeError } from '@shared/api/client'
import type { CommandList, BotCommand, CommandPolicies, CommandPolicy, CommandAcl } from '@shared/api/types'
import Panel from '@shared/ui/Panel.vue'
import DataTable from '@shared/ui/DataTable.vue'
import Notice from '@shared/ui/Notice.vue'
import Button from '@shared/ui/Button.vue'
import Tag from '@shared/ui/Tag.vue'
import Select from '@shared/ui/Select.vue'
import Input from '@shared/ui/Input.vue'

const loading = ref(false)
const error = ref('')
const commands = ref<BotCommand[]>([])
const policies = ref<Record<number, CommandPolicy>>({})
const globalAcl = ref<CommandAcl | null>(null)

const ACCESS_LABEL: Record<string, string> = {
  all: '全体',
  admin: '管理员',
  owner: '群主',
  whitelist: '白名单',
  blacklist: '黑名单',
}

const SCOPE_LABEL: Record<string, string> = {
  GROUP: '本群',
  SOME: '指定群',
  ALL: '全部群',
}

async function load() {
  loading.value = true
  error.value = ''
  try {
    const list = await adminApi.get<CommandList>('/commands')
    commands.value = list.commands
    const pol = await adminApi.get<CommandPolicies>('/commands/policies')
    policies.value = Object.fromEntries(pol.policies.map((p) => [p.id, p]))
    globalAcl.value = await adminApi.get<CommandAcl>('/acl/global')
  } catch (e) { error.value = describeError(e) } finally {
    loading.value = false
  }
}

/** 没配过就是本群 —— 与后端同一条规则 */
function policyOf(c: BotCommand): CommandPolicy {
  return policies.value[c.id] ?? { id: c.id, scope: 'GROUP', groups: [], showGroup: false, showUser: false }
}

function scopeLabel(c: BotCommand) {
  const p = policyOf(c)
  return SCOPE_LABEL[p.scope] ?? p.scope
}

/** 数据范围不是本群的要标出来 —— 这一列就是这张表存在的理由 */
function isOpen(c: BotCommand) {
  return policyOf(c).scope !== 'GROUP'
}

/** 来源显示：只在跨群时有意义 */
function originHint(c: BotCommand) {
  if (!isOpen(c)) return '—'
  const p = policyOf(c)
  const parts: string[] = []
  if (p.showGroup) parts.push('群号')
  if (p.showUser) parts.push('QQ号')
  return parts.length ? parts.join(' + ') : '都不显示'
}

function accessLabel(c: BotCommand) {
  return ACCESS_LABEL[c.access] ?? c.access
}

const openCount = computed(() => commands.value.filter(isOpen).length)

// ==================== 名单录入 ====================

/**
 * 四个框分开填：白名单的 QQ 号、白名单的群号、黑名单的 QQ 号、黑名单的群号。
 *
 * 为什么不让人混在一个框里自动猜 —— 群号和纯数字的 QQ 号长得一模一样，
 * 猜错就是把整个群放进来或者封掉。
 */
const editingId = ref<number | null>(null)
const whiteUsers = ref('')
const blackUsers = ref('')
const whiteGroups = ref('')
const blackGroups = ref('')
const saving = ref(false)
const saved = ref('')

const commandOptions = computed(() =>
  commands.value.map((c) => ({ value: String(c.id), label: '/' + c.trigger.split('/').join(' /') }))
)

/** 一行一个号，逗号也算分隔 */
function parseIds(text: string): number[] {
  return text
    .split(/[\s,，、]+/)
    .map((s) => s.trim())
    .filter(Boolean)
    .map((s) => Number(s))
    .filter((n) => Number.isFinite(n) && n > 0)
}

function lines(ids: number[]): string {
  return ids.join('\n')
}

async function pick(idText: string) {
  const id = Number(idText)
  editingId.value = id
  saved.value = ''
  if (!id) {
    whiteUsers.value = blackUsers.value = whiteGroups.value = blackGroups.value = ''
    return
  }
  try {
    const acl = await adminApi.get<CommandAcl>('/commands/' + id + '/acl')
    whiteUsers.value = lines(acl.white.filter((a) => a.type === 'uid').map((a) => a.value))
    whiteGroups.value = lines(acl.white.filter((a) => a.type === 'gid').map((a) => a.value))
    blackUsers.value = lines(acl.black.filter((a) => a.type === 'uid').map((a) => a.value))
    blackGroups.value = lines(acl.black.filter((a) => a.type === 'gid').map((a) => a.value))
  } catch (e) { error.value = describeError(e) }
}

async function saveAcl() {
  if (!editingId.value) return
  saving.value = true
  error.value = ''
  try {
    await adminApi.post('/commands/' + editingId.value + '/acl', {
      white: [
        ...parseIds(whiteUsers.value).map((v) => ({ type: 'uid', value: v })),
        ...parseIds(whiteGroups.value).map((v) => ({ type: 'gid', value: v })),
      ],
      black: [
        ...parseIds(blackUsers.value).map((v) => ({ type: 'uid', value: v })),
        ...parseIds(blackGroups.value).map((v) => ({ type: 'gid', value: v })),
      ],
    })
    saved.value = '已保存'
    await load()
  } catch (e) { error.value = describeError(e) } finally {
    saving.value = false
  }
}

defineExpose({
  reload: load,
  isLoading: () => loading.value,
})

onMounted(load)
</script>

<template>
  <div class="panel-wrap">
    <Notice v-if="error" tone="error">{{ error }}</Notice>

    <Panel title="两把锁">
      <div class="rules">
        <div class="rule">
          <div class="rule-k">谁能用</div>
          <div class="rule-v">五档：全体 / 管理员 / 群主 / 白名单 / 黑名单。
            名单优先于档位，单命令的名单优先于全局名单。</div>
        </div>
        <div class="rule">
          <div class="rule-k">能看哪些群</div>
          <div class="rule-v">本群 / 指定群 / 全部群。<strong>默认本群</strong>，
            要跨群必须有人主动改。</div>
        </div>
        <div class="rule">
          <div class="rule-k">写永远只写本群</div>
          <div class="rule-v">放宽的只有"读"。否则一个人在 1 群发一条，
            可以伪装成 2 群的内容。</div>
        </div>
      </div>
      <p v-if="openCount" class="note">
        当前有 <strong>{{ openCount }}</strong> 条命令的数据范围不是本群。
      </p>
    </Panel>

    <Panel title="命令总览" :count="commands.length">
      <template #actions>
        <Button size="sm" :disabled="loading" @click="load">刷新</Button>
      </template>

      <DataTable :rows="commands.length" empty="还没有任何指令" empty-hint="到「指令」页新建一条">
        <thead>
          <tr>
            <th>命令</th>
            <th>谁能用</th>
            <th>数据范围</th>
            <th>来源显示</th>
            <th>状态</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="c in commands" :key="c.id">
            <td class="path">/{{ c.trigger.split('/').join(' /') }}</td>
            <td><Tag :tone="c.access === 'all' ? 'neutral' : 'warn'">{{ accessLabel(c) }}</Tag></td>
            <td>
              <Tag :tone="isOpen(c) ? 'bad' : 'neutral'">{{ scopeLabel(c) }}</Tag>
            </td>
            <td class="muted">{{ originHint(c) }}</td>
            <td>
              <Tag v-if="c.enabled" tone="good">启用</Tag>
              <span v-else class="muted">停用</span>
            </td>
          </tr>
        </tbody>
      </DataTable>
    </Panel>

    <Panel title="全局名单">
      <div class="acl">
        <div class="acl-col">
          <div class="acl-k">白名单 · {{ globalAcl?.white?.length ?? 0 }}</div>
          <div class="acl-v faint">
            名单里的人能用<strong>所有</strong>命令，还能盖过单命令的黑名单。
          </div>
        </div>
        <div class="acl-col">
          <div class="acl-k">黑名单 · {{ globalAcl?.black?.length ?? 0 }}</div>
          <div class="acl-v faint">
            名单里的人什么命令都用不了，除非他挂在某条命令的白名单里。
          </div>
        </div>
      </div>
      <p class="faint note">
        名单条目可以填 QQ 号，也可以填群号，填群号代表整个群。
      </p>

      <div class="acl-edit">
        <div class="acl-pick">
          <Select
            :model-value="editingId === null ? '' : String(editingId)"
            :options="commandOptions"
            aria-label="选择命令"
            @update:model-value="pick"
          />
        </div>

        <div v-if="editingId" class="boxes">
          <div class="box">
            <div class="box-k"><Tag tone="good">白名单 · QQ 号</Tag></div>
            <Input
              :model-value="whiteUsers" multiline :rows="4" placeholder="一行一个 QQ 号"
              aria-label="白名单 QQ 号" @update:model-value="whiteUsers = $event"
            />
          </div>
          <div class="box">
            <div class="box-k"><Tag tone="good">白名单 · 群号</Tag></div>
            <Input
              :model-value="whiteGroups" multiline :rows="4" placeholder="一行一个群号，代表整个群"
              aria-label="白名单群号" @update:model-value="whiteGroups = $event"
            />
          </div>
          <div class="box">
            <div class="box-k"><Tag tone="bad">黑名单 · QQ 号</Tag></div>
            <Input
              :model-value="blackUsers" multiline :rows="4" placeholder="一行一个 QQ 号"
              aria-label="黑名单 QQ 号" @update:model-value="blackUsers = $event"
            />
          </div>
          <div class="box">
            <div class="box-k"><Tag tone="bad">黑名单 · 群号</Tag></div>
            <Input
              :model-value="blackGroups" multiline :rows="4" placeholder="一行一个群号"
              aria-label="黑名单群号" @update:model-value="blackGroups = $event"
            />
          </div>
        </div>

        <div v-if="editingId" class="box-actions">
          <span class="faint small">{{ saved }}</span>
          <Button size="sm" variant="primary" :disabled="saving" @click="saveAcl">保存名单</Button>
        </div>
      </div>
    </Panel>
  </div>
</template>

<style scoped>
.panel-wrap { display: flex; flex-direction: column; gap: var(--sp-4); }

.rules { display: grid; grid-template-columns: repeat(auto-fit, minmax(220px, 1fr)); gap: var(--sp-4); }
.rule-k { color: var(--flame); font-size: var(--fs-meta); margin-bottom: var(--sp-1); }
.rule-v { color: var(--mist); font-size: var(--fs-meta); line-height: 1.75; }

.path { font-family: var(--font-mono); color: var(--flame); white-space: nowrap; }
.note { font-size: var(--fs-meta); margin: var(--sp-3) 0 0; }

.acl { display: grid; grid-template-columns: 1fr 1fr; gap: var(--sp-4); }

.acl-edit { margin-top: var(--sp-5); padding-top: var(--sp-4); border-top: 1px solid var(--hairline); }
.acl-pick { max-width: 360px; }
.boxes { display: grid; grid-template-columns: 1fr 1fr; gap: var(--sp-3); margin-top: var(--sp-3); }
.box-k { margin-bottom: var(--sp-1); }
.box-actions { display: flex; align-items: center; justify-content: flex-end; gap: var(--sp-3); margin-top: var(--sp-3); }
.small { font-size: var(--fs-meta); }
.acl-k { color: var(--ink); margin-bottom: var(--sp-1); }
.acl-v { font-size: var(--fs-meta); line-height: 1.7; }

@media (max-width: 900px) {
  .acl { grid-template-columns: 1fr; }
}
</style>
