<script setup lang="ts">
/**
 * 变量胶囊面板。
 *
 * 点击按钮时不抢输入框焦点，父组件据此把变量插入到"当前正在编辑的输入框"，
 * 所以回复、list 回复、help 回复共用同一套变量按钮。
 */
export interface VariableChip {
  name: string
  label: string
  example: string
  advanced?: boolean
}

defineProps<{ vars: VariableChip[] }>()
const emit = defineEmits<{ insert: [string] }>()

function insert(name: string) {
  emit('insert', '{' + name + '}')
}
</script>

<template>
  <section class="var-panel" aria-label="变量">
    <header class="var-head">
      <span>变量</span>
      <span class="var-hint">点一下插入当前编辑区</span>
    </header>
    <div class="var-list">
      <button
        v-for="v in vars"
        :key="v.name"
        type="button"
        class="var-chip"
        :class="{ advanced: v.advanced }"
        :title="v.label + '，例：' + v.example"
        @mousedown.prevent
        @click="insert(v.name)"
      >{{ '{' + v.name + '}' }}</button>
    </div>
  </section>
</template>

<style scoped>
.var-panel { display: flex; flex-direction: column; gap: 7px; padding-top: 4px; border-top: 1px solid var(--hairline); }
.var-head { display: flex; align-items: baseline; justify-content: space-between; gap: var(--sp-2); }
.var-head > span:first-child { color: var(--ink-3); font-size: var(--fs-meta); }
.var-hint { color: var(--ink-4); font-size: 10px; }
.var-list { display: flex; flex-wrap: wrap; gap: 4px; }
.var-chip {
  padding: 3px 6px;
  border: 1px solid var(--edge);
  border-radius: var(--r-xs);
  background: var(--stone-400);
  color: var(--ember);
  font-family: var(--font-mono);
  font-size: 10.5px;
  line-height: 1.4;
  cursor: pointer;
}
.var-chip:hover { border-color: var(--edge-hover); background: var(--stone-500); }
.var-chip.advanced { color: var(--ink-3); }
.var-chip:focus-visible { outline: 2px solid var(--ember); outline-offset: 1px; }
</style>
