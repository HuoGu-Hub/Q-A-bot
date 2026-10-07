<script setup lang="ts">
/**
 * 「页面信息 → 首页轮播」面板。
 *
 * 公开站首页那块轮播的增删改：上传 / 排序 / 启停 / 配链接与说明。
 *
 * ⚠️ **上传必须用原生 fetch**，不能用 adminApi.post：共享的 request() 会把 body
 *    JSON.stringify 并强行带上 content-type: application/json，FormData 会被它毁掉，
 *    而且 multipart 的 boundary 必须由浏览器自己生成。整个文件里只有这一处绕开 adminApi。
 *
 * 设置那 4 项走的是现成的 /admin/api/settings（和白名单同一套），不另造接口。
 */
import { computed, onMounted, ref } from 'vue'
import { adminApi, describeError } from '@shared/api/client'
import type {
  CarouselItem, CarouselItemResponse, CarouselItemsResponse, CarouselListResponse,
  SettingItem, SettingsView,
} from '@shared/api/types'
import { shortTime } from '@shared/utils/format'
import Panel from '@shared/ui/Panel.vue'
import Button from '@shared/ui/Button.vue'
import Input from '@shared/ui/Input.vue'
import Field from '@shared/ui/Field.vue'
import Switch from '@shared/ui/Switch.vue'
import Tag from '@shared/ui/Tag.vue'
import Notice from '@shared/ui/Notice.vue'
import Empty from '@shared/ui/Empty.vue'
import DataTable from '@shared/ui/DataTable.vue'
import Modal from '@shared/ui/Modal.vue'
import { toast } from '@shared/ui/toast'

/** 设置项的 key。四项都在后端白名单里，这里只负责挑出来展示 */
const K_ENABLED = 'app.site.carousel.enabled'
const K_INTERVAL = 'app.site.carousel.interval-ms'
const K_MAX_COUNT = 'app.site.carousel.max-count'
const K_MAX_SIZE = 'app.site.carousel.max-size-kb'
/** 展示顺序：先开关，再数值。后端分组顺序不保证，所以这里定死 */
const SETTING_ORDER = [K_ENABLED, K_INTERVAL, K_MAX_COUNT, K_MAX_SIZE]

const loading = ref(false)
const error = ref('')
/** 行内操作（上移/下移/启停/删除）进行中 —— 期间把所有按钮禁掉，避免连点 */
const busy = ref(false)

const items = ref<CarouselItem[]>([])
const list = ref<CarouselListResponse | null>(null)

// ---------- 上传 ----------
const fileInput = ref<HTMLInputElement | null>(null)
const newLink = ref('')
const newCaption = ref('')
const uploading = ref(false)
const uploadErr = ref('')

// ---------- 编辑弹窗 ----------
const editing = ref<CarouselItem | null>(null)
const editLink = ref('')
const editCaption = ref('')
const savingEdit = ref(false)

// ---------- 设置 ----------
const settingItems = ref<SettingItem[]>([])
const changed = ref<Record<string, unknown>>({})
const savingSettings = ref(false)
const settingsOk = ref('')
const settingsErr = ref('')
/** 设置拉过一次没有 —— 用来区分「还在读」和「后端根本没有这几项」 */
const settingsLoaded = ref(false)

// ==================== 读 ====================

async function load() {
  loading.value = true
  error.value = ''
  try {
    const r = await adminApi.get<CarouselListResponse>('/carousel')
    list.value = r
    items.value = r.items ?? []
  } catch (e) {
    error.value = describeError(e)
  } finally {
    loading.value = false
  }
}

/**
 * 设置单独拉：它挂了不该把下面的图片列表一起藏起来。
 *
 * 按 key 从全部分组里找，**不按分组名找** —— 分组名是后端文案，写死在这里迟早对不上。
 */
async function loadSettings() {
  settingsErr.value = ''
  try {
    const r = await adminApi.get<SettingsView>('/settings')
    const all = Object.values(r.groups ?? {}).flat()
    settingItems.value = SETTING_ORDER
      .map((k) => all.find((i) => i.key === k))
      .filter((x): x is SettingItem => Boolean(x))
    changed.value = {}
    settingsOk.value = ''
  } catch (e) {
    settingsErr.value = describeError(e)
  } finally {
    settingsLoaded.value = true
  }
}

onMounted(() => {
  void load()
  void loadSettings()
})

// ==================== 限额（决定上传能不能点）====================

const settingOf = (key: string) => settingItems.value.find((i) => i.key === key)

/** 改过的值优先（还没保存也能立刻反映到限额上），最后退回列表接口给的值 */
function limitOf(key: string, fallback: number): number {
  const it = settingOf(key)
  const v = it ? Number(changed.value[it.key] ?? it.value) : NaN
  if (Number.isFinite(v) && v > 0) return v
  return fallback > 0 ? fallback : 0
}

const maxCount = computed(() => limitOf(K_MAX_COUNT, list.value?.maxCount ?? 0))
const maxSizeKb = computed(() => limitOf(K_MAX_SIZE, list.value?.maxSizeKb ?? 0))
/**
 * 容器的接收上限（KB）。它比业务上限宽 —— 超过它请求会被中途掐断，
 * 浏览器只会说 "Failed to fetch"，所以先在前端挡一道、把话说清楚。
 */
const ceilingKb = computed(() => list.value?.uploadCeilingKb ?? 0)
const atMax = computed(() => maxCount.value > 0 && items.value.length >= maxCount.value)
const canUpload = computed(() => !uploading.value && !atMax.value)

/** 「单张 ≤ 2 MB」这种好读的写法 */
function sizeText(kb: number): string {
  if (kb <= 0) return '大小不限'
  if (kb < 1024) return '单张 ≤ ' + kb + ' KB'
  const mb = kb / 1024
  return '单张 ≤ ' + (Number.isInteger(mb) ? mb : mb.toFixed(1)) + ' MB'
}

const uploadHint = computed(() => {
  if (atMax.value) return '最多 ' + maxCount.value + ' 张，先删一张再传'
  const base = '支持 PNG / JPEG / WebP / GIF，' + sizeText(maxSizeKb.value)
  return maxCount.value > 0 ? base + '，最多 ' + maxCount.value + ' 张' : base
})

const listCount = computed(() => {
  const n = items.value.length
  return maxCount.value > 0 ? n + ' / ' + maxCount.value + ' 张' : n + ' 张'
})

const dimsText = (it: CarouselItem) =>
  (it.width && it.height ? it.width + '×' + it.height : '尺寸未知')

// ==================== 写：上传 ====================

function pickFile() {
  if (!canUpload.value) return
  uploadErr.value = ''
  fileInput.value?.click()
}

/** 「12 MB」这种写法（接收上限用；业务上限走 sizeText） */
function ceilingText(kb: number): string {
  if (kb <= 0) return '未知'
  const mb = kb / 1024
  return (Number.isInteger(mb) ? mb : mb.toFixed(1)) + ' MB'
}

const fileMb = (f: File) => (f.size / 1024 / 1024).toFixed(1)

/**
 * 把「请求没拿到响应」翻译成人话。
 *
 * <p>浏览器对这种情况只说一句 {@code Failed to fetch}，没有状态码、没有原因 ——
 * 这张提示的职责就是列出最可能的几种，并带上这张图有多大，让人能自己往下查。
 */
function uploadFailText(file: File, e: unknown): string {
  const raw = e instanceof Error ? e.message : String(e)
  const ce = ceilingKb.value
  return '这个文件 ' + fileMb(file) + ' MB，没能传出去（浏览器只给了一句「' + raw + '」，没有更多信息）。'
    + '最常见的原因是图片大到被服务器中途掐断'
    + (ce > 0 ? '（服务器接收上限 ' + ceilingText(ce) + '）' : '')
    + ' —— 压小一点再传就对了；'
    + '如果图本来就不大，那就是后端没在跑（或刚重启），又或者是中间的 vite / nginx 代理把大请求截断了。'
}

async function onFile(ev: Event) {
  const input = ev.target as HTMLInputElement
  const file = input.files?.[0]
  // 立刻清空：不然同一个文件连选两次不会再触发 change
  input.value = ''
  if (!file) return

  uploadErr.value = ''
  // 前端先挡两道，不用等传完再被拒（后端仍会再校验一次）
  if (maxSizeKb.value > 0 && file.size > maxSizeKb.value * 1024) {
    uploadErr.value = '这个文件 ' + fileMb(file) + ' MB，超过业务上限（' + sizeText(maxSizeKb.value)
      + '）—— 压小一点，或者到下面「轮播设置」里把上限调大'
    return
  }
  if (ceilingKb.value > 0 && file.size > ceilingKb.value * 1024) {
    uploadErr.value = '这个文件 ' + fileMb(file) + ' MB，超过**服务器接收上限**（'
      + ceilingText(ceilingKb.value) + '）—— 这么大的图根本传不上去，只能先压小'
    return
  }

  uploading.value = true
  try {
    const fd = new FormData()
    fd.append('file', file)
    fd.append('link', newLink.value.trim())
    fd.append('caption', newCaption.value.trim())
    // 不要手动设 content-type：浏览器要自己带 multipart 的 boundary
    const res = await fetch('/admin/api/carousel', {
      method: 'POST',
      credentials: 'same-origin',
      body: fd,
    })
    const body = await res.json().catch(() => null)
    if (!res.ok) {
      // 状态码能告诉我们不少事，别浪费
      if (res.status === 401) throw new Error('登录已过期 —— 重新登录后再传')
      if (res.status === 413) {
        throw new Error('图片太大：服务器接收上限是 ' + ceilingText(ceilingKb.value)
          + '，压小一点再传（业务上限 ' + sizeText(maxSizeKb.value) + '）')
      }
      if (res.status === 404) {
        throw new Error('后端没有这个接口 —— 多半是跑的旧版本，重新打包并重启后端')
      }
      throw new Error(body?.error
        || ('HTTP ' + res.status + '（服务器没给原因，看后端日志）'))
    }
    newLink.value = ''
    newCaption.value = ''
    toast('已上传，公开站刷新即生效')
    await load()
  } catch (e) {
    // fetch 的两种失败在这里汇合：拿到了响应但状态不对（上面的 Error），
    // 以及**根本没拿到响应**（TypeError: Failed to fetch）—— 后者要说清可能的原因
    uploadErr.value = e instanceof TypeError ? uploadFailText(file, e)
      : (e instanceof Error ? e.message : String(e))
  } finally {
    uploading.value = false
  }
}

// ==================== 写：行内操作 ====================

/** 后端返回整份新列表，直接替换 —— 顺序不在前端猜 */
function applyItems(r: CarouselItemsResponse) {
  items.value = r.items ?? []
}

function replaceItem(item: CarouselItem) {
  items.value = items.value.map((x) => (x.id === item.id ? item : x))
}

async function move(it: CarouselItem, delta: number) {
  busy.value = true
  try {
    applyItems(await adminApi.post<CarouselItemsResponse>('/carousel/' + it.id + '/move', { delta }))
  } catch (e) {
    toast(describeError(e), 'error')
  } finally {
    busy.value = false
  }
}

async function toggleEnabled(it: CarouselItem) {
  busy.value = true
  try {
    const r = await adminApi.post<CarouselItemResponse>('/carousel/' + it.id, { enabled: !it.enabled })
    replaceItem(r.item)
    toast(r.item.enabled ? '已启用，公开站刷新即出现' : '已停用，公开站不再显示')
  } catch (e) {
    toast(describeError(e), 'error')
  } finally {
    busy.value = false
  }
}

async function remove(it: CarouselItem) {
  const name = it.caption ? '「' + it.caption + '」' : '第 ' + (items.value.indexOf(it) + 1) + ' 张图'
  if (!window.confirm('删除 ' + name + '？\n\n首页立刻不再显示它，删掉后要重新上传才能回来。')) return
  busy.value = true
  try {
    applyItems(await adminApi.post<CarouselItemsResponse>('/carousel/' + it.id + '/delete'))
    toast('已删除')
  } catch (e) {
    toast(describeError(e), 'error')
  } finally {
    busy.value = false
  }
}

function openEdit(it: CarouselItem) {
  editing.value = it
  editLink.value = it.link ?? ''
  editCaption.value = it.caption ?? ''
}

async function saveEdit() {
  const it = editing.value
  if (!it) return
  savingEdit.value = true
  try {
    const r = await adminApi.post<CarouselItemResponse>('/carousel/' + it.id, {
      link: editLink.value.trim(),
      caption: editCaption.value.trim(),
    })
    replaceItem(r.item)
    editing.value = null
    toast('已保存')
  } catch (e) {
    toast(describeError(e), 'error')
  } finally {
    savingEdit.value = false
  }
}

// ==================== 写：设置 ====================

const settingsDirty = computed(() => Object.keys(changed.value).length > 0)
const sVal = (it: SettingItem) => String(changed.value[it.key] ?? it.value ?? '')
const sBool = (it: SettingItem) => Boolean(changed.value[it.key] ?? it.value)
const sChanged = (it: SettingItem) => it.key in changed.value

function onSettingEdit(it: SettingItem, v: unknown) {
  changed.value = { ...changed.value, [it.key]: v }
  settingsOk.value = ''
  settingsErr.value = ''
}

async function saveSettings() {
  if (!settingsDirty.value) { settingsOk.value = '没有改动'; return }
  savingSettings.value = true
  settingsErr.value = ''
  settingsOk.value = ''
  try {
    const r = await adminApi.post<{
      ok: boolean; applied: string[]; rejected: string[]; errors: Record<string, string>
    }>('/settings', { changes: changed.value })
    if (r.rejected?.length) {
      settingsErr.value = '这些配置项不允许修改：' + r.rejected.join(', ')
      return
    }
    if (r.errors && Object.keys(r.errors).length) {
      settingsErr.value = Object.values(r.errors).join('；')
      return
    }
    settingsOk.value = '已保存并立即生效（' + r.applied.length + ' 项）'
    // 重新拉：限额可能被改动了，上传按钮的可用性要跟着变
    await Promise.all([loadSettings(), load()])
  } catch (e) {
    settingsErr.value = describeError(e)
  } finally {
    savingSettings.value = false
  }
}
</script>

<template>
  <div class="wrap">
    <Notice v-if="error" tone="error">{{ error }}</Notice>

    <Panel title="轮播图" :count="listCount" hint="按这里的顺序在公开站首页展示">
      <div v-if="loading" class="muted">读取中…</div>

      <template v-else>
        <!-- ---------- 上传 ---------- -->
        <div class="upload">
          <Field label="跳转链接" hint="可空。点图片去哪；不填就是纯展示。站内写 /library，外链写全 https://">
            <Input
              :model-value="newLink"
              mono
              placeholder="/library 或 https://…"
              :disabled="uploading"
              @update:model-value="(v: string) => (newLink = v)"
            />
          </Field>
          <Field label="说明文字" hint="可空。压在这张图底部">
            <Input
              :model-value="newCaption"
              placeholder="比如：雾锁王国 1.0 更新内容"
              :disabled="uploading"
              @update:model-value="(v: string) => (newCaption = v)"
            />
          </Field>

          <div class="upload-bar">
            <!-- 真 input 藏起来，样式交给共享 Button；accept 只管选择器，真正的类型校验在后端 -->
            <!-- sr-only 也是"存在但看不见"：它仍然是表单控件，必须有名字，
                 否则读屏与自动化检查都只会看到一个无名的"选择文件"按钮。 -->
            <input
              ref="fileInput"
              class="sr-only"
              type="file"
              accept="image/png,image/jpeg,image/webp,image/gif"
              aria-label="选择要上传的图片文件"
              @change="onFile"
            >
            <Button variant="primary" :disabled="!canUpload" @click="pickFile">
              {{ uploading ? '上传中…' : '选择图片上传' }}
            </Button>
            <span class="faint">{{ uploadHint }}</span>
          </div>

          <Notice v-if="uploadErr" tone="error">{{ uploadErr }}</Notice>
        </div>

        <!-- ---------- 列表 ---------- -->
        <Empty
          v-if="!items.length"
          text="还没有图"
          hint="用上面的按钮传一张 —— 它会出现在公开站首页"
        />

        <DataTable v-else :rows="items.length">
          <thead>
            <tr>
              <th class="num">#</th>
              <th>预览</th>
              <th>说明 / 链接</th>
              <th>状态</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="(it, i) in items" :key="it.id">
              <td class="num">{{ i + 1 }}</td>
              <td>
                <!-- width/height 让浏览器提前留好位置：不写的话图片到位前缩略图高度是 0，
                     表格会"塌一下再撑开"（CLS）。后端读不出尺寸时是 0 → undefined，交给 CSS。 -->
                <img
                  class="thumb"
                  :src="it.imageUrl"
                  :alt="it.caption || '轮播图'"
                  :width="it.width || undefined"
                  :height="it.height || undefined"
                  loading="lazy"
                  decoding="async"
                >
              </td>
              <td>
                <div :class="{ faint: !it.caption }">{{ it.caption || '（没有说明）' }}</div>
                <div class="faint link-line">{{ it.link || '（点了不跳转）' }}</div>
                <div class="faint meta-line">{{ dimsText(it) }} · {{ shortTime(it.createdAt) }}</div>
              </td>
              <td>
                <Tag :tone="it.enabled ? 'good' : 'neutral'">{{ it.enabled ? '展示中' : '已停用' }}</Tag>
              </td>
              <td>
                <div class="ops">
                  <Button size="sm" :disabled="busy || i === 0" @click="move(it, -1)">上移</Button>
                  <Button size="sm" :disabled="busy || i === items.length - 1" @click="move(it, 1)">下移</Button>
                  <Button size="sm" :disabled="busy" @click="toggleEnabled(it)">
                    {{ it.enabled ? '停用' : '启用' }}
                  </Button>
                  <Button size="sm" :disabled="busy" @click="openEdit(it)">编辑</Button>
                  <Button size="sm" variant="danger" :disabled="busy" @click="remove(it)">删除</Button>
                </div>
              </td>
            </tr>
          </tbody>
        </DataTable>
      </template>
    </Panel>

    <!-- ---------- 设置 ---------- -->
    <Panel title="轮播设置" hint="保存后公开站刷新即生效">
      <Notice v-if="settingsErr" tone="error">{{ settingsErr }}</Notice>
      <Notice v-if="settingsOk" tone="ok">{{ settingsOk }}</Notice>

      <!-- 「单张大小上限」填得比服务器接收上限还大是没用的（请求会被容器掐断），
           所以把那道上限直接显示出来，省得人以为改大了就一定能传 -->
      <p v-if="ceilingKb > 0" class="cap-note faint">
        服务器接收上限 <b>{{ ceilingText(ceilingKb) }}</b> ——「单张大小上限」填得比它大不会生效：
        超过这个数的请求会在读完之前被容器掐断，浏览器只显示一句「Failed to fetch」。
      </p>

      <!-- 三种状态要分得开：还在读 / 读失败（上面的 Notice）/ 读到了但一项都没有 -->
      <div v-if="!settingItems.length && !settingsErr" class="muted">
        {{ settingsLoaded ? '后端白名单里没有 app.site.carousel.* 这几项' : '读取中…' }}
      </div>

      <template v-else>
        <Field
          v-for="it in settingItems"
          :key="it.key"
          :label="it.label"
          :hint="it.hint"
          :changed="sChanged(it)"
          :control-width="it.type === 'bool' ? '80px' : '200px'"
        >
          <Switch
            v-if="it.type === 'bool'"
            :model-value="sBool(it)"
            :aria-label="it.label"
            @update:model-value="onSettingEdit(it, $event)"
          />
          <Input
            v-else
            :model-value="sVal(it)"
            mono
            @update:model-value="(v: string) => onSettingEdit(it, v)"
          />
        </Field>

        <div class="settings-bar">
          <Button variant="primary" :disabled="savingSettings || !settingsDirty" @click="saveSettings">
            {{ savingSettings ? '保存中…' : '保存设置' }}
          </Button>
          <Button :disabled="savingSettings || !settingsDirty" @click="loadSettings">放弃改动</Button>
          <span class="faint">切换间隔单位是毫秒（4000 = 4 秒）</span>
        </div>
      </template>
    </Panel>

    <!-- ---------- 编辑弹窗 ---------- -->
    <Modal :open="!!editing" title="编辑这张图" @close="editing = null">
      <div class="edit-form">
        <Field label="跳转链接" hint="可空。站内写 /library，外链写全 https://">
          <Input
            :model-value="editLink"
            mono
            placeholder="/library 或 https://…"
            :disabled="savingEdit"
            @update:model-value="(v: string) => (editLink = v)"
          />
        </Field>
        <Field label="说明文字" hint="可空。压在这张图底部">
          <Input
            :model-value="editCaption"
            :disabled="savingEdit"
            @update:model-value="(v: string) => (editCaption = v)"
          />
        </Field>
      </div>
      <div class="edit-bar">
        <Button :disabled="savingEdit" @click="editing = null">取消</Button>
        <Button variant="primary" :disabled="savingEdit" @click="saveEdit">
          {{ savingEdit ? '保存中…' : '保存' }}
        </Button>
      </div>
    </Modal>
  </div>
</template>

<style scoped>
.wrap { display: flex; flex-direction: column; gap: var(--sp-4); }

/* 「服务器接收上限」那句提示：是说明不是警告，压低存在感 */
.cap-note { font-size: var(--fs-meta); line-height: var(--lh-normal); margin: 0 0 var(--sp-3); }
.cap-note b { font-family: var(--font-mono); color: var(--ink); }

/* ---------- 上传区 ---------- */
/* 上传是这个页面的主要动作，所以给它一块抬升面，和下面的列表分开 */
.upload {
  background: var(--surface-inset);
  border: 1px solid var(--edge-soft);
  border-radius: var(--r-sm);
  padding: var(--sp-2) var(--sp-3) var(--sp-3);
  margin-bottom: var(--sp-4);
  display: flex;
  flex-direction: column;
  gap: var(--sp-2);
}
.upload-bar { display: flex; align-items: center; gap: var(--sp-3); flex-wrap: wrap; }

/* ---------- 列表 ---------- */
/* 缩略图定死一个 16:9 的框：不这样的话每行高度会随图片比例跳 */
.thumb {
  width: 96px;
  height: 54px;
  object-fit: cover;
  border-radius: var(--r-xs);
  border: 1px solid var(--edge-soft);
  background: var(--surface-inset);
  display: block;
}
.link-line { overflow-wrap: anywhere; }
.meta-line { font-size: var(--fs-xs); }

.ops { display: flex; flex-wrap: wrap; gap: var(--sp-1); }

/* ---------- 设置区 ---------- */
.settings-bar {
  display: flex;
  align-items: center;
  gap: var(--sp-3);
  flex-wrap: wrap;
  margin-top: var(--sp-4);
  padding-top: var(--sp-4);
  border-top: 1px solid var(--hairline);
}

/* ---------- 弹窗 ---------- */
.edit-form { display: flex; flex-direction: column; }
.edit-bar {
  display: flex;
  justify-content: flex-end;
  gap: var(--sp-2);
  margin-top: var(--sp-4);
}
</style>
