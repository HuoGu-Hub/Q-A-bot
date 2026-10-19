<script setup lang="ts">
/**
 * 「定时任务」页 —— 管理端的排查台。
 *
 * 群里的 /list /output 与 /del /output 只服务本群、且只能撤自己设的。
 * 管理员缺的是三件事：跨群全景、撤掉任何一条、以及**看到失败原因** ——
 * 任务发失败之后群里只表现为「该收到的没收到」，last_error 那一列此前没有界面能看到。
 *
 * 只读 + 撤销，不做编辑：改内容改时间会让「这条到底发没发过」说不清，
 * 要改就撤了重建，撤销是软删除，原定的时刻还留在行里。
 */
import { computed, onMounted, ref } from 'vue'
import { adminApi, describeError } from '@shared/api/client'
import type { ScheduleList, AdminScheduleTask } from '@shared/api/types'
import Panel from '@shared/ui/Panel.vue'
import DataTable from '@shared/ui/DataTable.vue'
import Select from '@shared/ui/Select.vue'
import Notice from '@shared/ui/Notice.vue'
import Button from '@shared/ui/Button.vue'
import Tag from '@shared/ui/Tag.vue'

const loading = ref(false)
const error = ref('')
const data = ref<ScheduleList | null>(null)
const status = ref('pending')

const STATUS_OPTIONS = [
  { value: 'pending', label: '待发' },
  { value: 'failed', label: '失败（要看的）' },
  { value: 'expired', label: '过期（错过宽限期）' },
  { value: 'sent', label: '已发' },
  { value: 'canceled', label: '已撤销' },
  { value: 'all', label: '全部状态' },
]

/** Tag 支持的色号，写窄了才不会把 danger 这种不存在的值传进去 */
type Tone = 'good' | 'warn' | 'bad' | 'neutral' | 'flame'

/** 状态 → 中文与色号。sending 是抢行中的瞬时状态，也会出现在列表里 */
const STATUS_META: Record<string, { label: string; tone: Tone }> = {
  pending: { label: '待发', tone: 'good' },
  sending: { label: '发送中', tone: 'warn' },
  sent: { label: '已发', tone: 'neutral' },
  failed: { label: '失败', tone: 'bad' },
  expired: { label: '过期', tone: 'warn' },
  canceled: { label: '已撤销', tone: 'neutral' },
}

async function load() {
  loading.value = true
  error.value = ''
  try {
    data.value = await adminApi.get<ScheduleList>('/schedules?status=' + status.value)
  } catch (e) { error.value = describeError(e) } finally {
    loading.value = false
  }
}

function onStatus(v: string) {
  status.value = v
  load()
}

async function cancel(t: AdminScheduleTask) {
  if (!window.confirm('确定撤销 #' + t.id + ' ？撤销后不再发送')) return
  try {
    await adminApi.post('/schedules/cancel', { id: t.id })
    await load()
  } catch (e) { error.value = describeError(e) }
}

/** 只有还挂着的能撤 */
function canCancel(t: AdminScheduleTask) {
  return t.status === 'pending' || t.status === 'sending'
}

/**
 * 时刻按东八区显示，与 bot 的口径一致。
 *
 * 不写死时区的话，管理端换一台机器看就是另一个时间 —— 而「21:30 发」这个 21:30
 * 从填进去那一刻起指的就是东八区。
 */
function fmt(iso?: string | null) {
  if (!iso) return '—'
  const d = new Date(iso)
  if (Number.isNaN(d.getTime())) return '—'
  return new Intl.DateTimeFormat('zh-CN', {
    timeZone: 'Asia/Shanghai',
    year: 'numeric', month: '2-digit', day: '2-digit',
    hour: '2-digit', minute: '2-digit', hour12: false,
  }).format(d)
}

function statusOf(t: AdminScheduleTask): { label: string; tone: Tone } {
  return STATUS_META[t.status] ?? { label: t.status, tone: 'neutral' }
}

function targetOf(t: AdminScheduleTask) {
  return t.groupId > 0 ? '群 ' + t.groupId : '私聊'
}



const counts = computed(() => data.value?.counts ?? {})

defineExpose({
  reload: load,
  isLoading: () => loading.value,
})

onMounted(load)
</script>

<template>
  <div class="panel-wrap">
    <Notice v-if="error" tone="error">{{ error }}</Notice>

    <Panel title="概览">
      <div class="counts">
        <div class="count">
          <span class="n">{{ counts.pending ?? 0 }}</span>
          <span class="k">待发</span>
        </div>
        <div class="count">
          <span class="n n--bad">{{ counts.failed ?? 0 }}</span>
          <span class="k">失败</span>
        </div>
        <div class="count">
          <span class="n n--warn">{{ counts.expired ?? 0 }}</span>
          <span class="k">过期</span>
        </div>
        <div class="count">
          <span class="n">{{ counts.sent ?? 0 }}</span>
          <span class="k">已发</span>
        </div>
        <div class="count">
          <span class="n">{{ counts.canceled ?? 0 }}</span>
          <span class="k">已撤销</span>
        </div>
      </div>
      <p class="faint note">
        失败与过期是排查重点：失败意味着协议层或出站过滤挡下了它，过期意味着那段时间服务没在跑。
        两者原文都在「最近错误」列。
      </p>
    </Panel>

    <Panel title="任务列表" :count="data?.tasks?.length">
      <template #actions>
        <Select
          :model-value="status"
          :options="STATUS_OPTIONS"
          aria-label="状态筛选"
          @update:model-value="onStatus"
        />
        <Button size="sm" :disabled="loading" @click="load">刷新</Button>
      </template>

      <DataTable
        :rows="data?.tasks?.length ?? 0"
        empty="没有符合条件的任务"
        empty-hint="换个状态看看，或者群里还没有人设置定时输出"
      >
        <thead>
          <tr>
            <th>编号</th>
            <th>目标</th>
            <th>内容</th>
            <th>什么时候发</th>
            <th>下一次</th>
            <th>创建者</th>
            <th>状态</th>
            <th>最近错误</th>
            <th class="ops">操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="t in data?.tasks ?? []" :key="t.id">
            <td class="id">#{{ t.id }}</td>
            <td class="muted">{{ targetOf(t) }}</td>
            <td class="content">{{ t.content }}</td>
            <td class="when">{{ t.when }}</td>
            <td class="mono">{{ fmt(t.fireAt) }}</td>
            <td class="muted">{{ t.nickname || t.userId }}</td>
            <td>
              <Tag :tone="statusOf(t).tone">{{ statusOf(t).label }}</Tag>
              <Tag v-if="t.attempts > 0" tone="neutral">试过 {{ t.attempts }} 次</Tag>
            </td>
            <td class="err">{{ t.lastError || '—' }}</td>
            <td class="ops">
              <Button v-if="canCancel(t)" size="sm" variant="danger" @click="cancel(t)">撤销</Button>
            </td>
          </tr>
        </tbody>
      </DataTable>
    </Panel>
  </div>
</template>

<style scoped>
.panel-wrap { display: flex; flex-direction: column; gap: var(--sp-4); }

.counts { display: flex; flex-wrap: wrap; gap: var(--sp-5); }
.count { display: flex; flex-direction: column; }
.n { font-size: 22px; color: var(--moss); font-variant-numeric: tabular-nums; }
.n--bad { color: var(--rust); }
.n--warn { color: var(--amber); }
.k { font-size: var(--fs-meta); color: var(--mist); }
.note { font-size: var(--fs-meta); margin: var(--sp-3) 0 0; }

.id { font-family: var(--font-mono); color: var(--flame); }
.mono { font-family: var(--font-mono); font-size: var(--fs-meta); white-space: nowrap; }
.content { max-width: 260px; overflow-wrap: anywhere; }
.when { font-size: var(--fs-meta); white-space: nowrap; color: var(--ink-2); }
/* 错误原文可能很长，限宽 + 可换行，别把表格撑爆 */
.err { max-width: 260px; font-size: var(--fs-meta); color: var(--rust); overflow-wrap: anywhere; }

.panel-wrap :deep(th.ops),
.panel-wrap :deep(td.ops) { text-align: right; white-space: nowrap; }
</style>
