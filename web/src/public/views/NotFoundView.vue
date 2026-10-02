<script setup lang="ts">
import { t } from '../useSiteText'
import { renderInline } from '../renderInline'
import Panel from '@shared/ui/Panel.vue'
import Button from '@shared/ui/Button.vue'
</script>

<template>
  <div class="page nf">
    <Panel corners>
      <!-- 文案可配置，默认值保留原文：拿不到后端文案时照常显示 -->
      <h1 class="t"><span v-html="renderInline(t('notfound.title', '迷失在雾里了'))" /></h1>
      <p class="muted"><span v-html="renderInline(t('notfound.desc', '这个页面不存在。也许你该回到有火光的地方。'))" /></p>
      <RouterLink to="/"><Button variant="primary">回到首页</Button></RouterLink>
    </Panel>
  </div>
</template>

<style scoped>
.nf { padding: var(--sp-8) var(--sp-4); max-width: 560px; text-align: center; }
.t { font-size: var(--fs-2xl); margin-bottom: var(--sp-2); }
</style>
