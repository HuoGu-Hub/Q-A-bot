<script setup lang="ts">
/**
 * 「Bot 配置 → 语音」面板。
 *
 * 素材库的日常操作都在这：拖拽上传、试听、改标签、启停、试发到群、删除。
 *
 * ⚠️ 上传必须用原生 fetch（同 CarouselPanel）：共享的 request() 会把 body
 *    JSON.stringify 并强塞 content-type: application/json，multipart 的 boundary
 *    会被它毁掉。整个面板只有这一处绕开 adminApi。
 */
import { onMounted, ref } from 'vue'
import { adminApi, describeError } from '@shared/api/client'
import Panel from '@shared/ui/Panel.vue'
import Button from '@shared/ui/Button.vue'
import Input from '@shared/ui/Input.vue'
import Switch from '@shared/ui/Switch.vue'
import Tag from '@shared/ui/Tag.vue'
import Notice from '@shared/ui/Notice.vue'
import Empty from '@shared/ui/Empty.vue'
import Modal from '@shared/ui/Modal.vue'
import Icon from '@shared/ui/Icon.vue'
import { toast } from '@shared/ui/toast'

interface VoiceItem {
  id: number
  title: string
  aliases: string
  tags: string
  mime: string
  sizeKb: number
  durationMs: number
  durationText: string
  enabled: boolean
  createdAt: string
  audioUrl: string
}

interface VoiceList {
  ok: boolean
  items: VoiceItem[]
  maxSizeKb: number
  maxSeconds: number
  enabled: boolean
}

const loading = ref(false)
const error = ref('')
const items = ref<VoiceItem[]>([])
const limits = ref({ maxSizeKb: 8192, maxSeconds: 300 })

// ---------- 拖拽上传 ----------
const fileInput = ref<HTMLInputElement | null>(null)
/** 拖拽悬停高亮。dragenter/leave 会在子元素间反复触发，用计数抵消 */
const dragDepth = ref(0)
const dragging = ref(false)
/** 正在上传的文件数；大于 0 时禁用再次投放 */
const pending = ref(0)
const total = ref(0)
/** 拖进来的文件默认打这个标签，省得一个个改 */
const defaultTags = ref('')

// ---------- 编辑 ----------
const editing = ref<VoiceItem | null>(null)
const editTitle = ref('')
const editAliases = ref('')
const editTags = ref('')
const savingEdit = ref(false)

// ---------- 试发 ----------
const sending = ref<VoiceItem | null>(null)
const groupId = ref(localStorage.getItem('voice.lastGroup') ?? '')
const sendingBusy = ref(false)

async function load() {
  loading.value = true
  error.value = ''
  try {
    const r = await adminApi.get<VoiceList>('/voice')
    items.value = r.items ?? []
    limits.value = { maxSizeKb: r.maxSizeKb, maxSeconds: r.maxSeconds }
  } catch (e) {
    error.value = describeError(e)
  } finally {
    loading.value = false
  }
}

// ==================== 上传 ====================

function onDragEnter(e: DragEvent) {
  e.preventDefault()
  dragDepth.value++
  dragging.value = true
}

function onDragOver(e: DragEvent) {
  e.preventDefault()
  if (e.dataTransfer) e.dataTransfer.dropEffect = 'copy'
}

function onDragLeave(e: DragEvent) {
  e.preventDefault()
  dragDepth.value = Math.max(0, dragDepth.value - 1)
  if (dragDepth.value === 0) dragging.value = false
}

function onDrop(e: DragEvent) {
  e.preventDefault()
  dragDepth.value = 0
  dragging.value = false
  const files = Array.from(e.dataTransfer?.files ?? [])
  void uploadFiles(files)
}

function pick(e: Event) {
  const input = e.target as HTMLInputElement
  const files = Array.from(input.files ?? [])
  input.value = ''
  void uploadFiles(files)
}

/**
 * 逐个上传。串行而不是并发：一次拖十首歌时，
 * 并发会把内存和 NapCat 的上传通道一起顶住，失败还难定位。
 */
async function uploadFiles(files: File[]) {
  if (files.length === 0 || pending.value > 0) return
  total.value = files.length
  pending.value = files.length
  let ok = 0
  let dup = 0
  const failures: string[] = []

  for (const file of files) {
    const form = new FormData()
    form.append('file', file)
    form.append('title', file.name.replace(/\.[^.]+$/, ''))
    form.append('aliases', '')
    form.append('tags', defaultTags.value)
    try {
      const res = await fetch('/admin/api/voice', {
        method: 'POST',
        credentials: 'same-origin',
        body: form,
      })
      const body = await res.json().catch(() => ({}))
      if (!res.ok) {
        failures.push(file.name + '：' + (body?.error ?? 'HTTP ' + res.status))
      } else if (body.duplicate) {
        dup++
      } else {
        ok++
      }
    } catch {
      failures.push(file.name + '：无法连接服务端')
    } finally {
      pending.value--
    }
  }

  await load()
  if (failures.length > 0) {
    toast(failures[0] + (failures.length > 1 ? '（还有 ' + (failures.length - 1) + ' 个失败）' : ''))
  } else if (ok + dup > 0) {
    toast('已上传 ' + ok + ' 个' + (dup > 0 ? '，' + dup + ' 个已存在' : ''))
  }
}

// ==================== 行内操作 ====================

async function toggle(item: VoiceItem, enabled: boolean) {
  try {
    await adminApi.post('/voice/' + item.id, { enabled })
    item.enabled = enabled
  } catch (e) {
    toast(describeError(e))
  }
}

async function remove(item: VoiceItem) {
  if (!window.confirm('删除「' + item.title + '」？文件也会一起删掉。')) return
  try {
    await adminApi.post('/voice/' + item.id + '/delete')
    await load()
    toast('已删除')
  } catch (e) {
    toast(describeError(e))
  }
}

function openEdit(item: VoiceItem) {
  editing.value = item
  editTitle.value = item.title
  editAliases.value = item.aliases
  editTags.value = item.tags
}

async function saveEdit() {
  if (!editing.value) return
  savingEdit.value = true
  try {
    await adminApi.post('/voice/' + editing.value.id, {
      title: editTitle.value,
      aliases: editAliases.value,
      tags: editTags.value,
    })
    editing.value = null
    await load()
    toast('已保存')
  } catch (e) {
    toast(describeError(e))
  } finally {
    savingEdit.value = false
  }
}

function openSend(item: VoiceItem) {
  sending.value = item
}

async function confirmSend() {
  if (!sending.value) return
  const gid = Number(groupId.value)
  if (!gid) {
    toast('先填群号')
    return
  }
  sendingBusy.value = true
  try {
    await adminApi.post('/voice/' + sending.value.id + '/send', { groupId: gid })
    localStorage.setItem('voice.lastGroup', String(gid))
    toast('已发到群 ' + gid)
    sending.value = null
  } catch (e) {
    toast(describeError(e))
  } finally {
    sendingBusy.value = false
  }
}

defineExpose({
  reload: load,
  isLoading: () => loading.value,
  isAvailable: () => !error.value,
})

onMounted(load)
</script>

<template>
  <div class="voice">
    <Notice v-if="error" tone="error">{{ error }}</Notice>

    <Panel title="上传音频" hint="拖进来就行，支持一次拖多个；单条不超过 5 分钟">
      <div class="upload">
        <div class="drop-row">
          <button
            type="button"
            class="drop"
            :class="{ over: dragging, busy: pending > 0 }"
            :disabled="pending > 0"
            @click="fileInput?.click()"
            @dragenter="onDragEnter"
            @dragover="onDragOver"
            @dragleave="onDragLeave"
            @drop="onDrop"
          >
            <Icon name="voice" :size="26" />
            <span class="drop-main">
              {{ pending > 0 ? '正在上传 ' + (total - pending + 1) + ' / ' + total : '把音频拖到这里' }}
            </span>
            <span class="drop-sub">也可以点击选择，支持 mp3 / wav / m4a / flac / ogg / amr / aac</span>
          </button>
          <div class="defaults">
            <label class="def-label" for="voice-tags">默认标签</label>
            <Input id="voice-tags" v-model="defaultTags" placeholder="例如 歌曲,副歌" />
            <p class="hint">只影响这一次拖进来的文件，之后可以在列表里改。</p>
          </div>
        </div>
        <input
          ref="fileInput"
          class="file"
          type="file"
          multiple
          accept="audio/*,.mp3,.wav,.m4a,.flac,.ogg,.amr,.aac"
          @change="pick"
        >
      </div>
    </Panel>

    <Panel title="素材库" :count="items.length" hint="关键词只负责触发语音，具体放哪一段由模型挑">
      <Empty v-if="!loading && items.length === 0" text="还没有语音" hint="拖一段音频到上面的框里就能用了" />
      <table v-else class="vt">
        <thead>
          <tr>
            <th>名称</th>
            <th>时长</th>
            <th>大小</th>
            <th>标签</th>
            <th>试听</th>
            <th>启用</th>
            <th aria-label="操作"></th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="it in items" :key="it.id" :class="{ off: !it.enabled }">
            <td>
              <div class="title">{{ it.title }}</div>
              <div v-if="it.aliases" class="sub">别名 {{ it.aliases }}</div>
            </td>
            <td class="num">{{ it.durationText }}</td>
            <td class="num">{{ it.sizeKb }} KB</td>
            <td>
              <Tag v-for="t in it.tags.split(',').filter(Boolean)" :key="t" tone="neutral">{{ t }}</Tag>
            </td>
            <td><audio :src="it.audioUrl" controls preload="none" /></td>
            <td><Switch :model-value="it.enabled" aria-label="启用" @update:model-value="(v: boolean) => toggle(it, v)" /></td>
            <td class="ops">
              <Button size="sm" @click="openEdit(it)">编辑</Button>
              <Button size="sm" @click="openSend(it)">发到群</Button>
              <Button size="sm" variant="danger" @click="remove(it)">删除</Button>
            </td>
          </tr>
        </tbody>
      </table>
    </Panel>

    <Modal :open="!!editing" title="编辑素材" width="520px" @close="editing = null">
      <div class="edit-form">
        <label class="def-label" for="edit-title">名称</label>
        <Input id="edit-title" v-model="editTitle" placeholder="例如 晴天副歌" />
        <label class="def-label" for="edit-aliases">别名</label>
        <Input id="edit-aliases" v-model="editAliases" placeholder="逗号分隔，例如 周杰伦,情歌" />
        <label class="def-label" for="edit-tags">标签</label>
        <Input id="edit-tags" v-model="editTags" placeholder="逗号分隔，例如 歌曲,副歌" />
      </div>
      <div class="modal-actions">
        <Button @click="editing = null">取消</Button>
        <Button variant="primary" :disabled="savingEdit" @click="saveEdit">
          {{ savingEdit ? '保存中…' : '保存' }}
        </Button>
      </div>
    </Modal>

    <Modal :open="!!sending" title="发到群" width="420px" @close="sending = null">
      <p class="hint">
        {{ sending?.title }} · {{ sending?.durationText }}，会立刻作为语音条发出。
      </p>
      <Input v-model="groupId" placeholder="群号" mono />
      <div class="modal-actions">
        <Button @click="sending = null">取消</Button>
        <Button variant="primary" :disabled="sendingBusy" @click="confirmSend">
          {{ sendingBusy ? '发送中…' : '发送' }}
        </Button>
      </div>
    </Modal>
  </div>
</template>

<style scoped>
.voice { display: flex; flex-direction: column; gap: var(--sp-4); }

.upload { display: flex; flex-direction: column; gap: var(--sp-3); }
.drop-row { display: grid; grid-template-columns: minmax(0, 1fr) 260px; gap: var(--sp-4); align-items: stretch; }

.drop {
  display: flex; flex-direction: column; align-items: center; justify-content: center;
  gap: 6px; padding: var(--sp-5) var(--sp-4); min-height: 132px;
  background: transparent; color: var(--fg-dim);
  border: 1.5px dashed var(--line-strong, var(--line)); border-radius: var(--r-md, 10px);
  cursor: pointer; text-align: center;
  transition: border-color .15s, background .15s, color .15s;
}
.drop:hover { border-color: var(--accent, var(--flame)); color: var(--fg); }
.drop.over {
  border-color: var(--accent, var(--flame));
  background: color-mix(in srgb, var(--accent, var(--flame)) 8%, transparent);
  color: var(--fg);
}
.drop.busy { cursor: progress; }
.drop-main { font-size: var(--fs-md); color: var(--fg); }
.drop-sub { font-size: var(--fs-xs); }

.defaults { display: flex; flex-direction: column; gap: 6px; justify-content: center; }
.def-label { font-size: var(--fs-xs); color: var(--fg-dim); }
.hint { color: var(--fg-dim); font-size: var(--fs-xs); margin: 0; }
.file { display: none; }

.vt { width: 100%; border-collapse: collapse; font-size: var(--fs-sm); }
.vt th {
  text-align: left; font-weight: 600; color: var(--fg-dim);
  padding: var(--sp-2); border-bottom: 1px solid var(--line);
}
.vt td { padding: var(--sp-2); border-bottom: 1px solid var(--line); vertical-align: middle; }
.vt tr.off { opacity: .55; }
.title { font-weight: 600; }
.sub { color: var(--fg-dim); font-size: var(--fs-xs); }
.num { font-variant-numeric: tabular-nums; color: var(--fg-dim); white-space: nowrap; }
.ops { display: flex; gap: var(--sp-2); justify-content: flex-end; }
audio { height: 32px; max-width: 200px; }

.edit-form { display: flex; flex-direction: column; gap: 6px; }
.modal-actions { display: flex; justify-content: flex-end; gap: var(--sp-2); margin-top: var(--sp-4); }

@media (max-width: 900px) {
  .drop-row { grid-template-columns: 1fr; }
  .ops { flex-wrap: wrap; }
}
@media (max-width: 760px) {
  .vt audio { max-width: 140px; }
}
</style>
