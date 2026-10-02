<script setup lang="ts">
/**
 * Bot 配置 —— 「设置」与「指令」合并后的页面。
 *
 * 为什么合并：这两个页面本来就是一件事的两半。设置管 `app.*` 的行为开关
 * （其中就有 `app.commands.*` 四项目前躺在设置页），指令管指令表本身；
 * 而指令页的文案还写着「可在 Bot 设置里开启」、模型页和广场页也都在文案里
 * 指向「设置」页 —— 交叉引用说明它们该在一起。
 *
 * tab 状态同步到 URL 查询串（`?tab=commands`），这样：
 *   ① 从旧的 /settings、/commands 跳过来能直接落到对应 tab
 *   ② 链接可分享、刷新后不跳回默认 tab
 *
 * 页面栏（吸顶）左边是二级目录、右边是本 tab 的动作按钮；按钮调的是面板
 * 内部状态（保存并生效、新建指令），所以面板用 defineExpose 把动作抛出来，
 * 这里按当前 tab 调对应的那个。
 *
 * ⚠️ **两个面板各用一个 ref，不能共用一个**。实测踩到的坑：Vue 3.5 把模板 ref
 * 的写入推迟到 post-render 队列（runtime-core 的 pendingSetRefMap），
 * 于是「切到某个 tab 的那一次渲染」里，ref 里还留着**上一个**面板的实例。
 * 共用一个 ref 时那是个**类型不对**的实例 —— 页面栏去调 `changedCount()`
 * 会直接抛 TypeError，整块工具栏渲染失败（按钮停在上一个 tab 的样子）。
 * 分开之后每个 ref 只会是「自己那个组件」或 null，可选链兜住 null 即可；
 * post-render 写入完成后会再重渲染一次，读数随即补齐。
 */
import { computed, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import AdminPage from '@shared/ui/AdminPage.vue'
import Tabs from '@shared/ui/Tabs.vue'
import Button from '@shared/ui/Button.vue'
import Tag from '@shared/ui/Tag.vue'
import SettingsPanel from './bot/SettingsPanel.vue'
import CommandsPanel from './bot/CommandsPanel.vue'

const route = useRoute()
const router = useRouter()

const TABS = [
  { key: 'settings', label: '设置' },
  { key: 'commands', label: '指令' },
]

const tab = ref(typeof route.query.tab === 'string' ? route.query.tab : 'settings')

// 浏览器前进/后退时跟着 URL 走
watch(
  () => route.query.tab,
  (v) => {
    if (typeof v === 'string' && v !== tab.value) tab.value = v
  }
)

function setTab(k: string) {
  tab.value = k
  router.replace({ query: { ...route.query, tab: k } })
}

/**
 * 面板句柄：两个面板各一个 ref，按当前 tab 取用（原因见文件头注释）。
 * 面板的公开动作一律是函数，所以视图在渲染时调用它读到的响应式依赖会计入
 * 视图的渲染副作用 —— 「已改 N 项」这类数字会跟着面板内部状态更新。
 */
const settingsRef = ref<InstanceType<typeof SettingsPanel> | null>(null)
const commandsRef = ref<InstanceType<typeof CommandsPanel> | null>(null)

const settings = computed(() => (tab.value === 'settings' ? settingsRef.value : null))
const commands = computed(() => (tab.value === 'commands' ? commandsRef.value : null))
</script>

<template>
  <AdminPage width="wide">
    <template #tabs>
      <Tabs :tabs="TABS" :model-value="tab" @update:model-value="setTab" />
    </template>

    <template #actions>
      <template v-if="tab === 'settings'">
        <Tag v-if="settings?.changedCount()" tone="warn">已改 {{ settings.changedCount() }} 项</Tag>
        <Button size="sm" @click="settings?.setAll(true)">全部展开</Button>
        <Button size="sm" @click="settings?.setAll(false)">全部收起</Button>
        <Button size="sm" :disabled="settings?.isLoading()" @click="settings?.reload()">放弃改动</Button>
        <Button size="sm" variant="primary" @click="settings?.save()">保存并生效</Button>
      </template>
      <template v-else>
        <Button size="sm" :disabled="commands?.isLoading()" @click="commands?.reload()">刷新</Button>
        <Button size="sm" variant="primary" @click="commands?.newCommand()">+ 新建指令</Button>
      </template>
    </template>

    <!--
      KeepAlive 是必须的：设置页有「改动缓冲」，改完没保存就切到指令页再切回来，
      组件被销毁会丢掉未提交的改动。缓存住两个面板即可。
    -->
    <KeepAlive>
      <SettingsPanel v-if="tab === 'settings'" ref="settingsRef" />
      <CommandsPanel v-else ref="commandsRef" />
    </KeepAlive>
  </AdminPage>
</template>
