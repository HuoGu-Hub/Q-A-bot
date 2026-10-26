<script setup lang="ts">
/**
 * 「Bot 配置 → 语音」面板。
 *
 * 素材库的日常操作都在这：上传、试听、启停、试发到群、删除。
 *
 * ⚠️ 上传必须用原生 fetch（同 CarouselPanel）：共享的 request() 会把 body
 *    JSON.stringify 并强塞 content-type: application/json，multipart 的 boundary
 *    会被它毁掉。整个面板只有这一处绕开 adminApi。
 */
import { computed, onMounted, ref } from 'vue'
import { adminApi, describeError } from '@shared/api/client'
import Panel from '@shared/ui/Panel.vue'
import Button from '@shared/ui/Button.vue'
import Input from '@shared/ui/Input.vue'
import Switch from '@shared/ui/Switch.vue'
import Tag from '@shared/ui/Tag.vue'
import Notice from '@shared/ui/Notice.vue'
import Empty from '@shared/ui/Empty.vue'
import Modal from '@shared/ui/Modal.vue'
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

// ---------- 上传 ----------
const fileInput = ref<HTMLInputElement | null>(null)
const picked = ref<File | null>(null)
const title = ref('')
const aliases = ref('')
const tags = ref('')
const uploading = ref(false)
const uploadError = ref('')

// ---------- 试发 ----------
const sending = ref<VoiceItem | null>(null)
const groupId = ref(localStorage.getItem('voice.lastGroup') ?? '')
const sendingBusy = ref(false)

const canUpload = computed(() => !!picked.value && !uploading.value)

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

function pick(e: Event) {
  const files = (e.target as HTMLInputElement).files
  picked.value = files && files.length > 0 ? files[0] : null
  uploadError.value = ''
  if (picked.value && !title.value) {
    title.value = picked.value.name.replace(/\.[^.]+$/, '')
  }
}

async function upload() {
  if (!picked.value) return
  uploading.value = true
  uploadError.value = ''
  const form = new FormData()
  form.append('file', picked.value)
  form.append('title', title.value)
  form.append('aliases', aliases.value)
  form.append('tags', tags.value)
  try {
    const res = await fetch('/admin/api/voice', {
      method: 'POST',
      credentials: 'same-origin',
      body: form,
    })
    const body = await res.json().catch(() => ({}))
    if (!res.ok) {
      uploadError.value = body?.error ?? 'HTTP ' + res.status
      return
    }
    toast(body.duplicate ? '这段音频库里已经有了，直接复用' : '已上传')
    picked.value = null
    title.value = ''
    aliases.value = ''
    tags.value = ''
    if (fileInput.value) fileInput.value.value = ''
    await load()
  } catch {
    uploadError.value = '无法连接服务端'
  } finally {
    uploading.value = false
  }
}

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

    <Panel title="上传音频" hint="mp3 / wav / m4a / flac / ogg / amr / aac，单条不超过 5 分钟">
      <div class="upload">
        <input
          ref="fileInput"
          class="file"
          type="file"
          accept="audio/*,.mp3,.wav,.m4a,.flac,.ogg,.amr,.aac"
          @change="pick"
        >
        <div class="fields">
          <Input v-model="title" placeholder="名称，例如 晴天副歌" />
          <Input v-model="aliases" placeholder="别名，逗号分隔，例如 周杰伦,情歌" />
          <Input v-model="tags" placeholder="标签，逗号分隔，例如 歌曲,副歌" />
          <Button variant="primary" :disabled="!canUpload" @click="upload">
            {{ uploading ? '上传中…' : '上传' }}
          </Button>
        </div>
        <p class="hint">
          选中的文件：{{ picked ? picked.name : '还没选' }}
          <span v-if="picked">（{{ Math.round(picked.size / 1024) }} KB）</span>
        </p>
        <Notice v-if="uploadError" tone="error">{{ uploadError }}</Notice>
      </div>
    </Panel>

    <Panel title="素材库" :count="items.length" hint="关键词只负责触发语音，具体放哪一段由模型挑">
      <Empty v-if="!loading && items.length === 0" text="还没有语音" hint="先上传一段，再点「发到群」试试" />
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
              <Button size="sm" @click="openSend(it)">发到群</Button>
              <Button size="sm" variant="danger" @click="remove(it)">删除</Button>
            </td>
          </tr>
        </tbody>
      </table>
    </Panel>

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
.file { font-size: var(--fs-sm); color: var(--fg-dim); }
.fields { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)) auto; gap: var(--sp-2); }
.hint { color: var(--fg-dim); font-size: var(--fs-sm); margin: 0; }

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
audio { height: 32px; max-width: 210px; }

.modal-actions { display: flex; justify-content: flex-end; gap: var(--sp-2); margin-top: var(--sp-4); }

@media (max-width: 760px) {
  .fields { grid-template-columns: 1fr; }
  .vt audio { max-width: 140px; }
}
</style>
