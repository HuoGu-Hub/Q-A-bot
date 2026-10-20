<script setup lang="ts">
/**
 * 板块记录弹窗。
 *
 * <p>记录是群里产生的数据，后台只做「看、撤销、恢复」—— 改正文会让
 * 「谁发的」变得不可信。撤销是软删，行还在，勾上「显示已撤销」就能恢复。
 */
import { computed, ref, watch } from 'vue'
import { adminApi, describeError } from '@shared/api/client'
import type { AdminBoard, AdminBoardRecord, AdminBoardRecordsResponse } from '@shared/api/types'
import { ellipsis, shortTime } from '@shared/utils/format'
import Modal from '@shared/ui/Modal.vue'
import DataTable from '@shared/ui/DataTable.vue'
import Button from '@shared/ui/Button.vue'
import Tag from '@shared/ui/Tag.vue'
import Notice from '@shared/ui/Notice.vue'

const props = defineProps<{
  open: boolean
  board: AdminBoard | null
}>()

const emit = defineEmits<{ close: [] }>()

const records = ref<AdminBoardRecord[]>([])
const loading = ref(false)
const error = ref('')
const withDeleted = ref(false)
const busyId = ref(0)

const title = computed(() => '板块记录 · /' + (props.board?.path ?? ''))

async function load() {
  if (!props.board) return
  loading.value = true
  error.value = ''
  try {
    const q = new URLSearchParams({
      board: props.board.path,
      withDeleted: String(withDeleted.value),
      limit: '200',
    })
    const data = await adminApi.get<AdminBoardRecordsResponse>('/board-records?' + q.toString())
    records.value = data.records
  } catch (e) {
    error.value = describeError(e)
  } finally {
    loading.value = false
  }
}

watch([() => props.open, () => props.board], ([open]) => { if (open) load() })
watch(withDeleted, () => { if (props.open) load() })

const STATUS: Record<string, { label: string; tone: 'good' | 'bad' | 'warn' | 'neutral' }> = {
  pending: { label: '待发', tone: 'warn' },
  sending: { label: '发送中', tone: 'warn' },
  sent: { label: '已发', tone: 'good' },
  failed: { label: '失败', tone: 'bad' },
  expired: { label: '过期', tone: 'neutral' },
  canceled: { label: '已取消', tone: 'neutral' },
}

function statusOf(record: AdminBoardRecord) {
  if (record.deleted) return { label: '已撤销', tone: 'neutral' as const }
  return STATUS[record.status] ?? { label: record.status, tone: 'neutral' as const }
}

function paramsText(params: Record<string, string>) {
  const text = Object.entries(params ?? {})
    .map(([name, value]) => '#' + name + '-' + value + '#')
    .join(' ')
  return ellipsis(text, 90)
}

async function remove(record: AdminBoardRecord) {
  if (!window.confirm('撤销 #' + record.id + '？记录不会真的删除，之后还能恢复。')) return
  busyId.value = record.id
  error.value = ''
  try {
    await adminApi.post('/board-records/delete', { id: record.id })
    await load()
  } catch (e) {
    error.value = describeError(e)
  } finally {
    busyId.value = 0
  }
}

async function restore(record: AdminBoardRecord) {
  busyId.value = record.id
  error.value = ''
  try {
    await adminApi.post('/board-records/restore', { id: record.id })
    await load()
  } catch (e) {
    error.value = describeError(e)
  } finally {
    busyId.value = 0
  }
}
</script>

<template>
  <Modal :open="open" :title="title" width="1060px" @close="emit('close')">
    <template #actions>
      <Button size="sm" :disabled="loading" @click="load">刷新</Button>
    </template>

    <div class="bar">
      <label class="check">
        <input v-model="withDeleted" type="checkbox" />
        <span>显示已撤销</span>
      </label>
      <span class="faint">{{ loading ? '读取中…' : records.length + ' 条' }}</span>
    </div>

    <Notice v-if="error" tone="error">{{ error }}</Notice>

    <div class="table-wrap">
      <DataTable
        :rows="records.length"
        empty="这个板块还没有记录"
        empty-hint="群里发 /板块 /create 添加一条"
      >
        <thead>
          <tr>
            <th>编号</th>
            <th>状态</th>
            <th>什么时候</th>
            <th>内容</th>
            <th>来自</th>
            <th class="ops">操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="r in records" :key="r.id" :class="{ 'is-deleted': r.deleted }">
            <td class="id">#{{ r.id }}</td>
            <td>
              <Tag :tone="statusOf(r).tone">{{ statusOf(r).label }}</Tag>
              <div v-if="r.attempts" class="mini faint">重试 {{ r.attempts }} 次</div>
              <div v-if="r.lastError" class="mini err" :title="r.lastError">{{ r.lastError }}</div>
            </td>
            <td class="when">
              <div>{{ r.when }}</div>
              <div v-if="r.deletedAt" class="mini faint">撤销于 {{ shortTime(r.deletedAt) }}</div>
            </td>
            <td>
              <div class="body-text" :title="r.text">{{ r.text || '（无正文）' }}</div>
              <div v-if="r.params && Object.keys(r.params).length" class="mini faint params">
                {{ paramsText(r.params) }}
              </div>
            </td>
            <td class="from">
              <div>{{ r.nickname || '—' }}</div>
              <div class="mini faint">群 {{ r.groupId }} · QQ {{ r.userId }}</div>
            </td>
            <td class="ops">
              <Button
                v-if="!r.deleted"
                size="sm"
                variant="danger"
                :disabled="busyId === r.id"
                @click="remove(r)"
              >撤销</Button>
              <Button
                v-else
                size="sm"
                :disabled="busyId === r.id"
                @click="restore(r)"
              >恢复</Button>
            </td>
          </tr>
        </tbody>
      </DataTable>
    </div>
  </Modal>
</template>

<style scoped>
.bar {
  display: flex; align-items: center; justify-content: space-between;
  gap: var(--sp-3); margin-bottom: var(--sp-3);
}
.check { display: flex; align-items: center; gap: 6px; font-size: var(--fs-meta); color: var(--ink-2); }
.check input { accent-color: var(--flame); }
.table-wrap { overflow-x: auto; }
.id { font-family: var(--font-mono); color: var(--flame); white-space: nowrap; }
.when { white-space: nowrap; }
.from { white-space: nowrap; }
.body-text { max-width: 360px; overflow-wrap: anywhere; line-height: 1.6; }
.params { max-width: 360px; overflow-wrap: anywhere; margin-top: 2px; }
.mini { font-size: 11px; line-height: 1.5; margin-top: 2px; }
.err {
  color: var(--blight-lift);
  max-width: 260px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
}
.is-deleted .body-text,
.is-deleted .when,
.is-deleted .from { opacity: .55; }
.faint { color: var(--ink-4); }
:deep(th.ops), :deep(td.ops) { text-align: right; white-space: nowrap; }
</style>
