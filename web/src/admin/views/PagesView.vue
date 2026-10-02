<script setup lang="ts">
/**
 * 页面信息 —— 管理公开站上的固定文案。
 *
 * 为什么要有这个页面：公开站原先有 40+ 处文案**硬编码在 .vue 里**，改一个字都要
 * 重新构建前端。这里把它们变成内容，管理员自己就能改。
 *
 * 两条设计约定（与后端一致）：
 *   ① 后端只存**被改过的**块，没改过的走代码里的默认值 —— 所以这个页面
 *      把默认值也显示出来，改之前能看到原来是啥。
 *   ② 保存空串 = 恢复默认（后端删掉覆盖行），不需要把默认值抄回来。
 *
 * 只管**人写死的字**：词条详情、资料库列表是数据驱动的，没有固定文案；
 * 广场的答案内容来自问答库。这个页面的块清单由后端注册表决定，前端不另写一份。
 */
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { adminApi, describeError } from '@shared/api/client'
import AdminPage from '@shared/ui/AdminPage.vue'
import Tabs from '@shared/ui/Tabs.vue'
import Panel from '@shared/ui/Panel.vue'
import Input from '@shared/ui/Input.vue'
import { toast } from '@shared/ui/toast'
import Button from '@shared/ui/Button.vue'
import Tag from '@shared/ui/Tag.vue'
import Modal from '@shared/ui/Modal.vue'
import { INLINE_MARKUP_HINT } from '@/public/renderInline'
import PagePreview from './pages/PagePreview.vue'
import CarouselPanel from './pages/CarouselPanel.vue'

interface Block {
  page: string; pageLabel: string; key: string; label: string; hint: string
  multiline: boolean; text: string; defaultText: string; overridden: boolean
}

const route = useRoute()
const router = useRouter()

const pages = ref<Record<string, Block[]>>({})
const loading = ref(false)
const error = ref('')
/** 改动缓冲：key = "page.block"。只有改过的块才提交 */
const changed = ref<Record<string, string>>({})
const saving = ref(false)

const pageKeys = computed(() => Object.keys(pages.value))
const pageTabs = computed(() => pageKeys.value.map(k => ({
  key: k,
  label: pages.value[k][0]?.pageLabel ?? k,
  count: pages.value[k].length,
})))

/**
 * 「首页轮播」不在后端那批**文案块**里（它是图片，不是文字），所以是一条**固定**的目录项，
 * 追加在文案页后面。⚠️ 别把它并进 pageTabs：那个列表是后端注册表驱动的，混进来会打架。
 */
const CAROUSEL_KEY = 'carousel'
const tabList = computed(() => [...pageTabs.value, { key: CAROUSEL_KEY, label: '首页轮播' }])

const valid = (k: unknown) =>
  typeof k === 'string' && (k === CAROUSEL_KEY || Object.prototype.hasOwnProperty.call(pages.value, k))
const page = ref('')
function setPage(k: string) {
  page.value = k
  router.replace({ query: { ...route.query, p: k } })
}

const blocks = computed(() => pages.value[page.value] ?? [])
const keyOf = (b: Block) => `${b.page}.${b.key}`
const val = (b: Block) => changed.value[keyOf(b)] ?? b.text
const isChanged = (b: Block) => keyOf(b) in changed.value && changed.value[keyOf(b)] !== b.text

/** 填写说明弹窗：写标记、占位符、多行块的规矩集中在一处，不再挤在每块下面 */
const helpOpen = ref(false)

/**
 * 预览的两个入参直接由当前 blocks 派生 —— 不另存一份状态，
 * 所以输入框一变这里就跟着变（`val(b)` 已经含了未保存的改动）。
 */
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
    // 落位：URL 指定 > 后端给的第一个页面 > 轮播（后端一个页面都没有时，至少还有它能用）
    const want = typeof route.query.p === 'string' && valid(route.query.p) ? String(route.query.p) : ''
    page.value = want || Object.keys(r.pages)[0] || CAROUSEL_KEY
  } catch (e) { toast(describeError(e), 'error') } finally {
    loading.value = false
  }
}

/** 保存本页改过的块。逐块提交 —— 后端是按块存的，没必要为它再造一个批量接口 */
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

/** 恢复默认：后端删掉覆盖行。传空串即可，不用抄默认值回去 */
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
  <AdminPage :width="page === CAROUSEL_KEY ? 'wide' : 'narrow'">
    <template #tabs>
      <Tabs v-if="tabList.length" :tabs="tabList" :model-value="page" @update:model-value="setPage" />
    </template>

    <template #actions>
      <!-- 轮播那一栏的按钮在它自己的面板里（列表一组、设置一组），页面栏这排文案动作对它没有意义 -->
      <template v-if="page !== CAROUSEL_KEY">
        <!-- 「本页 N 块」那行计数已删（2026-09-27）：它是给开发者看的数字，
             改过的块本身就有火色高亮，不需要再数一遍。换成一个说明按钮。 -->
        <Button size="sm" @click="helpOpen = true">填写说明</Button>
        <Button size="sm" :disabled="loading" @click="load">放弃改动</Button>
        <Button size="sm" :disabled="saving || loading" @click="resetPage">本页恢复默认</Button>
        <Button size="sm" variant="primary" :disabled="saving || loading" @click="savePage">
          {{ saving ? '保存中…' : '保存本页' }}
        </Button>
      </template>
    </template>

    <!-- 首页轮播：图片不是「文案块」，所以整块换成它自己的面板 -->
    <CarouselPanel v-if="page === CAROUSEL_KEY" />

    <div v-else class="cols">
      <div class="panel-wrap">
        <div v-if="loading" class="muted">读取中…</div>

        <Panel v-else-if="blocks.length" :title="blocks[0].pageLabel" :count="blocks.length + ' 块'">
          <div v-for="b in blocks" :key="keyOf(b)" class="blk" :class="{ changed: isChanged(b) }">
            <div class="blk-head">
              <span class="blk-label">{{ b.label }}</span>
              <Tag v-if="b.overridden" tone="flame">改过</Tag>
              <span v-if="b.hint" class="faint blk-hint">{{ b.hint }}</span>
              <span class="spacer" />
              <Button v-if="b.overridden" size="sm" :disabled="saving" @click="resetBlock(b)">恢复默认</Button>
            </div>
            <Input
              :model-value="val(b)"
              :multiline="b.multiline"
              :rows="3"
              @update:model-value="(v: string) => onEdit(b, v)"
            />
            <!--
              填写帮助（怎么写标记、能写哪些占位符）—— 它是「怎么填」的说明，
              所以跟着输入框走，而不是挂在页面工具栏上。
              原先这里放的是「默认：<原文>」，已删：恢复默认是按钮的事，
              把原文摊在每块下面只是噪声。
            -->
            <div class="faint blk-help">
              可用 <code>[文字](链接)</code>、<code>**加粗**</code>；占位符 <code>{kb}</code>、<code>{year}</code>
            </div>
          </div>
        </Panel>

        <p v-else class="faint note">这个页面没有可编辑的固定文案。</p>
      </div>

      <!-- 缩略预览：跟着编辑区滚动（sticky），窄屏落到底下 -->
      <aside v-if="blocks.length" class="preview-col">
        <div class="preview-head">
          <span class="preview-title">预览</span>
          <span class="faint preview-sub">{{ blocks[0].pageLabel }}</span>
        </div>
        <PagePreview
          :page="page"
          :texts="previewTexts"
          :defaults="previewDefaults"
        />
      </aside>
    </div>

    <!--
      填写说明弹窗。
      「怎么写标记、能用哪些占位符、多行块的规矩」以前散在每块下面的一行小字里，
      现在集中到一处：想查的时候点一下，不想看的时候不占位置。
    -->
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
/* 两栏：编辑 : 预览 = 4 : 6（用户指定）。
   预览是这一页的重点（它就是拿来看效果的），所以给它更大的份额；
   编辑区每块只有一两行文字，4 份足够。
   宽屏以下堆叠 —— 窄屏上并排会让两边都没法看。 */
.cols {
  display: grid;
  grid-template-columns: minmax(0, 2fr) minmax(0, 3fr);
  gap: var(--sp-5);
  align-items: start;
}

/* 预览跟随滚动：编辑区很长，滚到一半还想看到预览。
   top 要让开吸顶栏（--admin-bar-h）再留一点呼吸。 */
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
  /* 窄屏 4:6 会把编辑区压得太窄，这里改回等宽堆叠（预览仍在下面） */
  .cols { grid-template-columns: minmax(0, 1fr); }
  .preview-col { position: static; }
}

.panel-wrap { display: flex; flex-direction: column; gap: var(--sp-4); min-width: 0; }

/* 动作行已上移到页面栏（AdminPage#actions），这里不再自己排一条。
   .spacer 仍留给下面每个文案块的标题行（把「恢复默认」顶到右边）。 */
.spacer { flex: 1; }
/* 空态那句「这个页面没有可编辑的固定文案」用。
   原来这条是给工具栏那行「本页 N 块」计数的，计数删掉后样式留下给空态用。 */
.note { font-size: var(--fs-meta); margin: 0; }

/* ---------- 填写说明弹窗 ---------- */
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
/* 行内代码与编辑区那行提示同一套观感（凹槽底 + 等宽） */
.help code {
  padding: 0 4px;
  border-radius: 3px;
  background: var(--surface-inset);
  color: var(--ink-dim);
  font-family: var(--font-mono);
}
.help b { color: var(--ink); font-weight: var(--fw-medium); }

/* 每块文案：标题行 + 输入 + 默认值回显。
   同级之间用发丝线 + 留白分隔（留白比线更重要）。 */
.blk { display: flex; flex-direction: column; gap: var(--sp-2); padding: var(--sp-4) var(--sp-2); }
.blk + .blk { border-top: 1px solid var(--hairline); }
.blk:first-of-type { padding-top: var(--sp-2); }
/* 改动高亮：背景 + 内阴影，不动布局 */
.blk.changed { background: var(--flame-veil); box-shadow: inset 2px 0 0 var(--flame); border-radius: var(--r-sm); }

.blk-head { display: flex; align-items: center; gap: var(--sp-2); flex-wrap: wrap; }
.blk-label { font-size: var(--fs-body); font-weight: var(--fw-medium); }
.blk-hint { font-size: var(--fs-meta); }
.blk-help { font-size: var(--fs-meta); overflow-wrap: anywhere; }
.blk-help code {
  padding: 0 4px;
  border-radius: 3px;
  background: var(--surface-inset);
  color: var(--ink-dim);
  font-family: var(--font-mono);
}
</style>
