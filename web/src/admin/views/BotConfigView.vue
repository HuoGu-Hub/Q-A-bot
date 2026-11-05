<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import AdminPage from '@shared/ui/AdminPage.vue'
import Tabs from '@shared/ui/Tabs.vue'
import Button from '@shared/ui/Button.vue'
import Input from '@shared/ui/Input.vue'
import Tag from '@shared/ui/Tag.vue'
import SettingsPanel from './bot/SettingsPanel.vue'
import CommandsPanel from './bot/CommandsPanel.vue'
import VariablesPanel from './bot/VariablesPanel.vue'
import VoicePanel from './bot/VoicePanel.vue'

const route = useRoute()
const router = useRouter()

const TABS = [
  { key: 'settings', label: '设置' },
  { key: 'commands', label: '命令' },
  { key: 'variables', label: '变量' },
  { key: 'voice', label: '语音' },
]

const tab = ref(typeof route.query.tab === 'string' ? route.query.tab : 'settings')

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

const settingsRef = ref<InstanceType<typeof SettingsPanel> | null>(null)
const commandsRef = ref<InstanceType<typeof CommandsPanel> | null>(null)
const variablesRef = ref<InstanceType<typeof VariablesPanel> | null>(null)
const voiceRef = ref<InstanceType<typeof VoicePanel> | null>(null)

const settings = computed(() => (tab.value === 'settings' ? settingsRef.value : null))
const commands = computed(() => (tab.value === 'commands' ? commandsRef.value : null))
const variables = computed(() => (tab.value === 'variables' ? variablesRef.value : null))
const voice = computed(() => (tab.value === 'voice' ? voiceRef.value : null))
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
      <template v-else-if="tab === 'commands'">
        <Button size="sm" :disabled="commands?.isLoading()" @click="commands?.reload()">刷新</Button>
        <Button size="sm" variant="primary" @click="commands?.openNewMenu()">新建命令</Button>
      </template>
      <template v-else-if="tab === 'voice'">
        <Tag v-if="!voice?.isAvailable()" tone="warn">素材库未就绪</Tag>
        <Button size="sm" :disabled="voice?.isLoading()" @click="voice?.reload()">刷新</Button>
      </template>
      <template v-else>
        <Tag
          v-if="variables?.advancedOff()"
          tone="warn"
          title="高级变量指 {user.id} 与 {group.id}：需要在服务端打开 app.commands.allow-user-ids 才会开放"
        >{user.id}/{group.id} 未启用</Tag>
        <Button size="sm" @click="variables?.toggleMode()">
          {{ variables?.showCurrent() ? '看示例值' : '看当前值' }}
        </Button>
        <Input
          class="var-search"
          :model-value="variables?.keyword() ?? ''"
          placeholder="搜索变量"
          aria-label="搜索变量"
          @update:model-value="(v: string) => variables?.setKeyword(v)"
        />
        <Button size="sm" :disabled="variables?.isLoading()" @click="variables?.reload()">刷新</Button>
      </template>
    </template>

    <KeepAlive>
      <SettingsPanel v-if="tab === 'settings'" ref="settingsRef" />
      <CommandsPanel v-else-if="tab === 'commands'" ref="commandsRef" />
      <VoicePanel v-else-if="tab === 'voice'" ref="voiceRef" />
      <VariablesPanel v-else ref="variablesRef" />
    </KeepAlive>
  </AdminPage>
</template>

<style scoped>
.var-search { width: 220px; }

@media (max-width: 760px) {
  .var-search { width: 148px; }
}
</style>
