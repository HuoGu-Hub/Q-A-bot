<script setup lang="ts">
/**
 * 知识库 —— 「文档」「词条」「分类」「提案」「资料」五个二级目录合并后的页面。
 *
 *  文档   往知识库**导入文档**（新存储：SQLite 块表，按块 id 操作）
 *          —— 这是切到新模型之后的主要入口
 *
 * 为什么合并：它们本来就是同一条链路的几个切面 ——
 *   分类（板块骨架） → 词条（名字 + 正文块） → 向量
 * 「词条」把原来的「关键词」与「术语核对」并成了一个面板：后端把术语表从
 * TSV 文件收成 kb_term 单表之后，这两块管的就是同一批对象（Wiki 页面），
 * 分成两个 tab 只会让人以为是两件事，还要维护两套状态机。
 *
 * 各二级目录的分工：
 *   词条   知识库里有什么：英文名、中文名/别名、它的文本块（可直接编辑覆盖），
 *         外加批量核对模式（逐个确认中文译名）
 *   分类   知识库的骨架：Wiki 原始分类 → 9 个玩家能懂的大类（不参与检索，只影响浏览）
 *   提案   被认可的回答攒够阈值后，由 agent 产出、等人确认的改进提案
 *   资料   往知识库加东西：投递审核、邀请码、建立索引
 *
 * tab 状态同步到 URL 查询串，这样旧的 /glossary、/keywords、/categories
 * 跳过来能直接落位。
 *
 * 页面栏（吸顶）左边是二级目录、右边是**当前 tab** 的动作：筛选控件、进度读数、
 * 刷新 / 保存 / 新建这类按钮原来各自待在面板自己的工具栏行里，现在都收上来，
 * 全页只剩这一条。按钮调的是面板内部状态，所以面板用 defineExpose 抛动作，
 * 这里按当前 tab 调对应的那个。
 *
 * ⚠️ **五个面板各用一个 ref，不能共用一个**。实测踩到的坑：Vue 3.5 把模板 ref
 * 的写入推迟到 post-render 队列（runtime-core 的 pendingSetRefMap），
 * 于是「切到某个 tab 的那一次渲染」里，ref 里还留着**上一个**面板的实例。
 * 共用一个 ref 时那是个**类型不对**的实例 —— 页面栏去调 `progress()` 会直接抛
 * TypeError，整条工具栏渲染失败、停在上一个 tab 的样子。
 * 分开之后每个 ref 只会是「自己那个组件」或 null，可选链兜住 null 即可；
 * post-render 写入完成后会再重渲染一次，读数随即补齐。
 */
import { computed, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import AdminPage from '@shared/ui/AdminPage.vue'
import Tabs from '@shared/ui/Tabs.vue'
import Button from '@shared/ui/Button.vue'
import Input from '@shared/ui/Input.vue'
import Select from '@shared/ui/Select.vue'
import Tag from '@shared/ui/Tag.vue'
import TermPanel from './kb/TermPanel.vue'
import CategoryPanel from './kb/CategoryPanel.vue'
import ProposalPanel from './kb/ProposalPanel.vue'
import DocPanel from './kb/DocPanel.vue'

const route = useRoute()
const router = useRouter()

const TABS = [
  // 「文档」放第一位：切到新存储之后，"把符合格式的文档导进来"是这个页面最主要的事，
  // 其余几个 tab 是配套（名字 / 分类 / 提案 / 投递）。
  { key: 'docs', label: '文档' },
  { key: 'terms', label: '词条' },
  { key: 'category', label: '分类' },
  { key: 'proposals', label: '提案' },
]

const valid = (k: unknown) => typeof k === 'string' && TABS.some(t => t.key === k)
const tab = ref(valid(route.query.tab) ? String(route.query.tab) : 'docs')

watch(() => route.query.tab, (v) => {
  if (valid(v) && v !== tab.value) tab.value = String(v)
})

function setTab(k: string) {
  tab.value = k
  router.replace({ query: { ...route.query, tab: k } })
}

/**
 * 五个面板各一个 ref，按当前 tab 取用（原因见文件头注释）。
 * 每个 ref 的类型就是它自己那个组件，永远不会拿到别的面板的实例。
 */
const termRef = ref<InstanceType<typeof TermPanel> | null>(null)
const catRef = ref<InstanceType<typeof CategoryPanel> | null>(null)
const propRef = ref<InstanceType<typeof ProposalPanel> | null>(null)
const docRef = ref<InstanceType<typeof DocPanel> | null>(null)

const tm = computed(() => (tab.value === 'terms' ? termRef.value : null))
const cat = computed(() => (tab.value === 'category' ? catRef.value : null))
const prop = computed(() => (tab.value === 'proposals' ? propRef.value : null))
const doc = computed(() => (tab.value === 'docs' ? docRef.value : null))

/**
 * 读数统一在这里取好再给模板用。
 *
 * 面板的暴露项都是函数，直接写进模板要连着一串可选链（`tm?.progress()?.pct`），
 * 又长又要小心空值；收进 computed 后模板只剩变量名。
 * 这些 computed 会去调面板的函数 —— 读到的响应式依赖照样计入，
 * 所以面板里数字一变，页面栏就跟着变。
 */
const tmView = computed(() => tm.value?.viewValue() ?? 'main')
const tmViewOptions = computed(() => tm.value?.viewOptions())
const tmKeyword = computed(() => tm.value?.keywordValue() ?? '')
const tmMode = computed(() => tm.value?.currentMode() ?? 'list')
const tmProgress = computed(() => tm.value?.progress() ?? null)
const tmDraft = computed(() => tm.value?.draftCount() ?? 0)
const tmLoading = computed(() => tm.value?.isLoading() ?? false)

const catDirty = computed(() => cat.value?.editedCount() ?? 0)
const catLoading = computed(() => cat.value?.isLoading() ?? false)

const propProgress = computed(() => prop.value?.progress() ?? null)
const propAnalyzing = computed(() => prop.value?.analyzing() ?? false)
const propLoading = computed(() => prop.value?.isLoading() ?? false)
const propCanAnalyze = computed(() => prop.value?.canAnalyze() ?? false)
const propTitle = computed(() => prop.value?.analyzeTitle() ?? '')

const docLoading = computed(() => doc.value?.isLoading() ?? false)
</script>

<template>
  <AdminPage width="wide">
    <template #tabs>
      <Tabs :tabs="TABS" :model-value="tab" @update:model-value="setTab" />
    </template>

    <template #actions>
      <!--
        词条：动作比别的 tab 多，因为它把「看内容」和「核对名字」合成了一处。
        顺序按使用频率排：先筛（视图预设 / 搜索），再切模式，最后才是新建与
        Excel 旁路 —— 后者是低频动作，放在最右边不挡路。
      -->
      <template v-if="tab === 'terms'">
        <Select
          class="w-view"
          :model-value="tmView"
          :options="tmViewOptions"
          @update:model-value="(v: string) => tm?.pickView(v)"
        />
        <Input
          class="w-search"
          :model-value="tmKeyword"
          placeholder="搜英文名 / 中文名 / 别名"
          @update:model-value="(v: string) => tm?.setKeyword(v)"
          @keyup.enter="tm?.search()"
        />
        <Button size="sm" :variant="tmMode === 'list' ? 'primary' : 'ghost'"
                @click="tm?.switchMode('list')">列表</Button>
        <Button size="sm" :variant="tmMode === 'review' ? 'primary' : 'ghost'"
                @click="tm?.switchMode('review')">
          核对模式<template v-if="tmDraft">（{{ tmDraft }}）</template>
        </Button>
        <!-- 核对进度只在核对模式显示 —— 列表模式放这个说明反而是噪音 -->
        <span v-if="tmProgress && tmMode === 'review'" class="stat faint">
          核对进度 <b>{{ tmProgress.processed }}</b> / <b>{{ tmProgress.total }}</b>
          （<b>{{ tmProgress.pct }}</b>%）
        </span>
        <Button size="sm" @click="termRef?.openCreate()">新建词条</Button>
        <Button size="sm" :disabled="tmLoading" @click="tm?.pickImport()">导入</Button>
        <Button size="sm" :disabled="tmLoading" @click="tm?.exportTsv()">导出</Button>
        <!-- 不可逆，所以在面板里会再弹一次确认 -->
        <Button size="sm" variant="danger" :disabled="tmLoading" @click="tm?.clearAllTerms()">
          清空词条
        </Button>
        <Button size="sm" :disabled="tmLoading" @click="tm?.reload()">
          {{ tmLoading ? '读取中…' : '刷新' }}
        </Button>
      </template>

      <!-- 分类：未保存计数 + 重新载入 / 保存改动 -->
      <template v-else-if="tab === 'category'">
        <Tag v-if="catDirty" tone="warn">未保存 {{ catDirty }} 条</Tag>
        <Button size="sm" :disabled="catLoading" @click="cat?.reload()">重新载入</Button>
        <Button size="sm" :disabled="!catDirty" variant="primary" @click="cat?.saveAll()">
          保存改动
        </Button>
      </template>

      <!-- 提案：待分析进度 + 刷新 / 让 agent 分析 -->
      <template v-else-if="tab === 'proposals'">
        <span v-if="propProgress" class="stat faint">
          <template v-if="!propProgress.available">提案库不可用</template>
          <template v-else>
            <b>{{ propProgress.pendingGood }}</b> / <b>{{ propProgress.threshold }}</b>
            条「有帮助」待分析
            <template v-if="propProgress.canAnalyze"> · <span class="reach">可以分析</span></template>
            <template v-else> · 还差 {{ propProgress.remaining }} 条</template>
          </template>
        </span>
        <Button size="sm" :disabled="propLoading" @click="prop?.reload()">刷新</Button>
        <Button
          size="sm"
          variant="primary"
          :disabled="propAnalyzing || propLoading || !propCanAnalyze"
          :title="propTitle"
          @click="prop?.analyze()"
        >
          {{ propAnalyzing ? '分析中…' : '让 agent 分析' }}
        </Button>
      </template>

      <!-- 文档：块存储的刷新（导入与块操作都在面板里） -->
      <template v-else-if="tab === 'docs'">
        <Button size="sm" :disabled="docLoading" @click="docRef?.reload()">
          {{ docLoading ? '读取中…' : '刷新' }}
        </Button>
      </template>

    </template>

    <!--
      KeepAlive：词条面板里可能有正在编辑但没保存的文本块 / 中文名，
      核对模式还可能有勾选到一半的批次。切个 tab 就丢掉这些是不可接受的。
    -->
    <KeepAlive>
      <DocPanel v-if="tab === 'docs'" ref="docRef" />
      <TermPanel v-else-if="tab === 'terms'" ref="termRef" />
      <CategoryPanel v-else-if="tab === 'category'" ref="catRef" />
      <ProposalPanel v-else-if="tab === 'proposals'" ref="propRef" />
    </KeepAlive>
  </AdminPage>
</template>

<style scoped>
/* 页面栏里的筛选控件。共享 Input / Select 自带 width:100%，
   所以定宽用 flex-basis（不去和 width 打平手，也不看两份样式谁先注入）。
   视图预设的标签带计数（「待核对 · 3357」），给宽一点免得被截断。 */
.w-view { flex: 0 0 176px; }
.w-search { flex: 0 0 220px; }

/* 页面栏里的进度读数：等宽 + tabular-nums，数字位数变化时整行不会左右抖 */
.stat { font-size: var(--fs-meta); color: var(--ink-dim); }
.stat b { font-family: var(--font-mono); font-variant-numeric: tabular-nums; font-weight: var(--fw-semi); }
/* 够阈值 = 可以动手，给唯一的暖色信号 */
.reach { color: var(--moss); }

@media (max-width: 900px) {
  .w-view, .w-search { flex: 1 1 100%; }
}
</style>
