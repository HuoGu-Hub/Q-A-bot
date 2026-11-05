<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { adminApi, describeError } from '@shared/api/client'
import AdminPage from '@shared/ui/AdminPage.vue'
import Tabs from '@shared/ui/Tabs.vue'
import Input from '@shared/ui/Input.vue'
import { toast } from '@shared/ui/toast'
import Button from '@shared/ui/Button.vue'
import Tag from '@shared/ui/Tag.vue'
import Modal from '@shared/ui/Modal.vue'
import { INLINE_MARKUP_HINT } from '@/public/renderInline'
import { botName } from '@shared/identity/botName'
import PagePreview from './pages/PagePreview.vue'
import CarouselPanel from './pages/CarouselPanel.vue'
import SiteInfoPanel from './pages/SiteInfoPanel.vue'

interface Block {
  page: string; pageLabel: string; key: string; label: string; hint: string
  multiline: boolean; text: string; defaultText: string; overridden: boolean
}

const route = useRoute()
const router = useRouter()

const pages = ref<Record<string, Block[]>>({})
const loading = ref(false)
const error = ref('')
const changed = ref<Record<string, string>>({})
const saving = ref(false)

const pageKeys = computed(() => Object.keys(pages.value))
const pageTabs = computed(() => pageKeys.value.map(k => ({
  key: k,
  label: pages.value[k][0]?.pageLabel ?? k,
  count: pages.value[k].length,
})))

const CAROUSEL_KEY = 'carousel'

const SITE_KEY = 'site'

const tabList = computed(() => [
  ...pageTabs.value,
  { key: CAROUSEL_KEY, label: '首页轮播' },
  { key: SITE_KEY, label: '站点信息' },
])

const valid = (k: unknown) =>
  typeof k === 'string'
  && (k === CAROUSEL_KEY || k === SITE_KEY || Object.prototype.hasOwnProperty.call(pages.value, k))
const page = ref('')
function setPage(k: string) {
  page.value = k
  router.replace({ query: { ...route.query, p: k } })
}

const blocks = computed(() => pages.value[page.value] ?? [])
const keyOf = (b: Block) => `${b.page}.${b.key}`
const val = (b: Block) => changed.value[keyOf(b)] ?? b.text
const isChanged = (b: Block) => keyOf(b) in changed.value && changed.value[keyOf(b)] !== b.text

const overriddenCount = computed(() => blocks.value.filter(b => b.overridden).length)
const dirtyCount = computed(() => blocks.value.filter(isChanged).length)

function discardLocal(b: Block) {
  const next = { ...changed.value }
  delete next[keyOf(b)]
  changed.value = next
}

const helpOpen = ref(false)

const previewTexts = computed<Record<string, string>>(() =>
  Object.fromEntries(blocks.value.map(b => [b.key, val(b)])))
const previewDefaults = computed<Record<string, string>>(() =>
  Object.fromEntries(blocks.value.map(b => [b.key, b.defaultText])))

function onEdit(b: Block, v: string) {
  changed.value = { ...changed.value, [keyOf(b)]: v }
}

async function load() {
  loading.value = true
  try {
    const r = await adminApi.get<{ pages: Record<string, Block[]>; pageLabels: Record<string, string> }>('/pages')
    pages.value = r.pages
    changed.value = {}
    const want = typeof route.query.p === 'string' && valid(route.query.p) ? String(route.query.p) : ''
    page.value = want || Object.keys(r.pages)[0] || CAROUSEL_KEY
  } catch (e) { toast(describeError(e), 'error') } finally {
    loading.value = false
  }
}

async function savePage() {
  const dirty = blocks.value.filter(isChanged)
  if (!dirty.length) { toast('本页没有改动', 'info'); return }
  saving.value = true
  try {
    for (const b of dirty) {
      await adminApi.post('/pages', { page: b.page, key: b.key, text: changed.value[keyOf(b)] })
    }
    toast(`已保存 ${dirty.length} 块，公开站刷新即生效`)
    await load()
  } catch (e) { toast(describeError(e), 'error') } finally {
    saving.value = false
  }
}

async function resetBlock(b: Block) {
  try {
    await adminApi.post('/pages', { page: b.page, key: b.key, text: '' })
    toast(`「${b.label}」已恢复默认`)
    await load()
  } catch (e) { toast(describeError(e), 'error') }
}

async function resetPage() {
  const dirty = blocks.value.filter(b => b.overridden || isChanged(b))
  if (!dirty.length) { toast('本页本来就是默认值', 'info'); return }
  if (!window.confirm(`把本页 ${dirty.length} 块全部恢复默认？`)) return
  saving.value = true
  try {
    for (const b of dirty) {
      await adminApi.post('/pages', { page: b.page, key: b.key, text: '' })
    }
    toast(`本页 ${dirty.length} 块已恢复默认`)
    await load()
  } catch (e) { toast(describeError(e), 'error') } finally { saving.value = false }
}

onMounted(load)
</script>

<template>
  <AdminPage width="wide">
    <template #tabs>
      <Tabs v-if="tabList.length" :tabs="tabList" :model-value="page" @update:model-value="setPage" />
    </template>

    <template #actions>
      <template v-if="page !== CAROUSEL_KEY && page !== SITE_KEY">
        <Button size="sm" @click="helpOpen = true">填写说明</Button>
        <Button size="sm" :disabled="loading" @click="load">放弃改动</Button>
        <Button size="sm" :disabled="saving || loading" @click="resetPage">本页恢复默认</Button>
        <Button size="sm" variant="primary" :disabled="saving || loading" @click="savePage">
          {{ saving ? '保存中…' : '保存本页' }}
        </Button>
      </template>
    </template>

    <CarouselPanel v-if="page === CAROUSEL_KEY" />

    <SiteInfoPanel v-else-if="page === SITE_KEY" />

    <div v-else class="cols">
      <div class="galley texture-noise">
        <div v-if="loading" class="muted">读取中…</div>

        <template v-else-if="blocks.length">
          <div v-if="overriddenCount || dirtyCount" class="galley-status">
            <span v-if="overriddenCount" class="status-item">
              <b class="num">{{ overriddenCount }}</b> 块改过
            </span>
            <span v-if="dirtyCount" class="status-item status-item--dirty">
              <b class="num">{{ dirtyCount }}</b> 处未保存
            </span>
          </div>

          <div v-for="b in blocks" :key="keyOf(b)" class="blk"
               :class="{ changed: b.overridden || isChanged(b) }">
            <div class="blk-head">
              <span class="blk-label">{{ b.label }}</span>
              <Tag v-if="b.overridden" tone="flame">改过</Tag>
              <span v-if="b.overridden || isChanged(b)" class="blk-actions">
                <Button v-if="isChanged(b)" size="sm" @click="discardLocal(b)">撤销修改</Button>
                <Button v-if="b.overridden" size="sm" :disabled="saving" @click="resetBlock(b)">
                  恢复默认
                </Button>
              </span>
            </div>
            <Input
              :model-value="val(b)"
              :multiline="b.multiline"
              :rows="3"
              :aria-label="b.label"
              @update:model-value="(v: string) => onEdit(b, v)"
            />

          </div>
        </template>

        <p v-else class="faint note">这个页面没有可编辑的固定文案。</p>
      </div>

      <aside v-if="blocks.length" class="preview-col">
        <div class="preview-head">
          <span class="preview-title">预览</span>
          <span class="faint preview-sub">{{ blocks[0].pageLabel }}</span>
        </div>
        <PagePreview
          :page="page"
          :texts="previewTexts"
          :defaults="previewDefaults"
          :bot-name="botName"
        />
      </aside>
    </div>

    <Modal :open="helpOpen" title="填写说明" @close="helpOpen = false">
      <div class="help">
        <p class="help-lead">
          这些块是公开站上的固定文案。改完点「保存本页」，公开站刷新即生效；
          <b>把内容清空再保存 = 恢复默认值</b>（不用把原文抄回来）。
        </p>

        <h4 class="help-h">行内标记</h4>
        <ul class="help-list">
          <li>
            <code>[文字](链接)</code> → 链接。站内互链直接写 <code>/library</code>，
            外链要写全 <code>https://…</code>（只放行 http/https）
          </li>
          <li><code>**文字**</code> → 加粗</li>
        </ul>
        <p class="help-note">
          {{ INLINE_MARKUP_HINT }}，就这两种；HTML 标签或其它写法会<b>原样显示</b>，不会被执行。
        </p>

        <h4 class="help-h">占位符</h4>
        <ul class="help-list">
          <li><code>{kb}</code> → 知识库条目数（目前只有首页「副标语」用得上）</li>
          <li><code>{year}</code> → 当前年份（页脚版权行用）</li>
          <li><code>{bot}</code> → 机器人昵称（读 Bot 配置里的「机器人身份」）</li>
        </ul>
        <p class="help-note">认不出的占位符会原样留在页面上 —— 不会被悄悄吃掉，也就看得出来写错了。</p>

        <h4 class="help-h">多行块：一行一条</h4>
        <ul class="help-list">
          <li>
            首页「条目」、关于页「回答怎么产生」的正文是<b>逐行</b>渲染的：
            每一行变成列表的一项，空行忽略。
          </li>
          <li>不要把序号写进去（<code>1.</code> <code>2.</code>）—— 序号是自动加的，写了会重复。</li>
        </ul>
      </div>
    </Modal>
  </AdminPage>
</template>

<style scoped>
.cols {
  display: grid;
  grid-template-columns: minmax(0, 2fr) minmax(0, 3fr);
  gap: var(--sp-5);
  align-items: start;
}

.preview-col {
  position: sticky;
  top: calc(var(--admin-bar-h, 0px) + var(--sp-4));
  display: flex;
  flex-direction: column;
  gap: var(--sp-2);
  padding: var(--sp-3);
  background: var(--surface-panel);
  border: 1px solid var(--edge-soft);
  border-radius: var(--r-md);
}
.preview-head { display: flex; align-items: baseline; gap: var(--sp-2); }
.preview-title { font-size: var(--fs-section); font-weight: var(--fw-semi); }
.preview-sub { font-size: var(--fs-meta); }

@media (max-width: 1100px) {
  .cols { grid-template-columns: minmax(0, 1fr); }
  .preview-col { position: static; }
}

.galley {
  display: flex;
  flex-direction: column;
  min-width: 0;
  background: var(--stone-300);
  border-radius: var(--r-md);
  padding: var(--sp-5);
  box-shadow: var(--bevel-raised);
}

.galley-status {
  display: flex;
  align-items: center;
  gap: var(--sp-4);
  padding: 0 var(--sp-2) var(--sp-3);
  font-size: var(--fs-meta);
  color: var(--ink-faint);
  border-bottom: 1px solid var(--hairline);
}
.status-item { display: inline-flex; align-items: baseline; gap: 4px; }
.status-item b { font-size: var(--fs-sm); font-weight: var(--fw-medium); color: var(--ink-dim); }

.note { font-size: var(--fs-meta); margin: 0; }

.help { font-size: var(--fs-sm); color: var(--ink-dim); line-height: var(--lh-normal); }
.help-lead { margin: 0 0 var(--sp-4); }
.help-h {
  font-size: var(--fs-meta);
  font-weight: var(--fw-semi);
  color: var(--ink);
  margin: var(--sp-4) 0 var(--sp-2);
}
.help-h:first-of-type { margin-top: 0; }
.help-list { margin: 0; padding-left: 1.2em; display: flex; flex-direction: column; gap: var(--sp-1); }
.help-note { margin: var(--sp-2) 0 0; font-size: var(--fs-meta); color: var(--ink-faint); }
.help code {
  padding: 0 4px;
  border-radius: 3px;
  background: var(--surface-inset);
  color: var(--ink-dim);
  font-family: var(--font-mono);
}
.help b { color: var(--ink); font-weight: var(--fw-medium); }

.blk { display: flex; flex-direction: column; gap: var(--sp-2); padding: var(--sp-4) var(--sp-2); }
.blk + .blk { border-top: 1px solid var(--hairline); }
.blk.changed { box-shadow: inset 2px 0 0 var(--flame); border-radius: var(--r-sm); }

.blk-actions {
  display: flex;
  align-items: center;
  gap: var(--sp-2);
  margin-left: auto;
}

.blk-head { display: flex; align-items: center; gap: var(--sp-2); flex-wrap: wrap; }
.blk-label { font-size: var(--fs-body); font-weight: var(--fw-medium); }
</style>
