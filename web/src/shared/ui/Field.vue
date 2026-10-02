<script setup lang="ts">
/**
 * 表单行：左侧「标签 + 说明 + 配置键」，右侧控件。
 *
 * 抽它的原因：这个结构在设置页、分类页、模型页、知识库页各写了一遍，
 * 而且都用了「负 margin 贴边 + 2px 火焰左边线」来表示"已改动"——
 * 副作用是改动行会整体左移 12px，行宽随"是否改动"跳动。
 * 这里改成背景 + 内阴影，不动布局。
 */
withDefaults(
  defineProps<{
    label: string
    /** 一句话说明这个配置是干什么的 */
    hint?: string
    /** 已改动 —— 高亮但不位移 */
    changed?: boolean
    /** 控件列宽。默认 320px；开关类可以窄一点 */
    controlWidth?: string
  }>(),
  { changed: false, controlWidth: '320px' }
)
</script>

<template>
  <div class="field" :class="{ changed }">
    <div class="meta">
      <div class="label">
        {{ label }}
        <span v-if="changed" class="dot" title="已修改">●</span>
      </div>
      <div v-if="hint" class="hint">{{ hint }}</div>
    </div>
    <div class="ctrl">
      <slot />
    </div>
  </div>
</template>

<style scoped>
/* 同级之间用发丝线分隔，而不是给每一行再套一个框。
   线要看得见（α .10）；但"两两能不能一眼分开"主要靠上下留白。 */
.field + .field { border-top: 1px solid var(--hairline); }

.field {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(0, v-bind(controlWidth));
  gap: var(--sp-4);
  align-items: start;
  padding: var(--sp-3) var(--sp-2);
  border-radius: var(--r-sm);
  transition: background var(--dur-fast) var(--ease);
}
.field:first-child { padding-top: var(--sp-1); }
.field:last-child { padding-bottom: var(--sp-1); }

.field.changed {
  background: var(--flame-veil);
  box-shadow: inset 2px 0 0 var(--flame);
}

.meta { min-width: 0; }
.label { font-size: var(--fs-body); color: var(--ink); }
.dot { color: var(--flame); margin-left: 4px; }
.hint { font-size: var(--fs-meta); color: var(--ink-faint); margin-top: 2px; }
.ctrl { min-width: 0; }

@media (max-width: 760px) {
  .field { grid-template-columns: minmax(0, 1fr); gap: var(--sp-2); }
}
</style>
