<script setup lang="ts">
/**
 * 搜索条。
 *
 * 整条做成一个刻进去的槽：图标在槽里、清空在槽里、提交按钮贴在右端。
 * 上一版是 input 与按钮两个独立圆角方块并排，圆角还不一样（6px 配 3px）。
 *
 * 手机上按钮收成纯图标方块，把宽度全让给输入框；文字用 sr-only 保住可读名。
 */
import { ref } from 'vue'
import Icon from './Icon.vue'
import Button from './Button.vue'

const props = withDefaults(defineProps<{
  modelValue: string
  placeholder?: string
  /** 输入框的无障碍名，同时作为 label 文本 */
  label?: string
  busy?: boolean
  /** 提交按钮文字；空字符串表示只用图标 */
  action?: string
  /** 右侧是否显示清空按钮 */
  clearable?: boolean
}>(), {
  placeholder: '',
  label: '搜索',
  busy: false,
  action: '搜索',
  clearable: true,
})

const emit = defineEmits<{
  'update:modelValue': [string]
  submit: []
  clear: []
}>()

// 不用 useId：同一页可能出现多条搜索，随机串足够避免冲突，且不依赖 Vue 版本能力
const uid = 'sb-' + Math.random().toString(36).slice(2, 9)
const el = ref<HTMLInputElement | null>(null)

function clear() {
  emit('update:modelValue', '')
  emit('clear')
  el.value?.focus()
}
</script>

<template>
  <form class="search" role="search" @submit.prevent="emit('submit')">
    <span class="ico" aria-hidden="true"><Icon name="search" :size="18" /></span>
    <label class="sr-only" :for="uid">{{ label }}</label>
    <input
      :id="uid"
      ref="el"
      class="inp"
      type="search"
      :value="modelValue"
      :placeholder="placeholder"
      autocomplete="off"
      enterkeyhint="search"
      @input="emit('update:modelValue', ($event.target as HTMLInputElement).value)"
    >
    <button
      v-if="clearable && modelValue"
      type="button"
      class="clear"
      aria-label="清空"
      @click="clear"
    >
      <Icon name="close" :size="15" />
    </button>
    <Button class="go" variant="primary" type="submit" :disabled="busy">
      <Icon name="search" :size="16" class="go-ico" />
      <span v-if="action" class="go-txt">{{ busy ? '查找中' : action }}</span>
    </Button>
  </form>
</template>

<style scoped>
.search {
  display: flex;
  align-items: center;
  gap: var(--sp-2);
  max-width: 660px;
  padding: 5px 5px 5px var(--sp-4);
  background: var(--stone-void);
  border: 1px solid var(--edge);
  border-radius: var(--r-md);
  box-shadow: var(--bevel-inset);
  transition: border-color var(--dur-fast) var(--ease), box-shadow var(--dur-fast) var(--ease);
}
.search:focus-within {
  border-color: var(--ember);
  box-shadow: var(--bevel-inset), var(--focus-ring);
}
.ico { flex: none; color: var(--ink-3); display: grid; place-items: center; }
.inp {
  flex: 1;
  min-width: 0;
  /* 手机端必须 ≥16px，否则 iOS 聚焦时会放大整页 */
  font-size: var(--fs-base);
  background: none;
  border: 0;
  outline: none;
  padding: 8px 0;
  min-height: 32px;
  color: var(--ink);
}
.inp::placeholder { color: var(--ink-3); }
.inp::-webkit-search-cancel-button { display: none; }
.clear {
  flex: none;
  display: grid;
  place-items: center;
  width: 28px;
  height: 28px;
  border: 0;
  border-radius: 50%;
  background: var(--stone-400);
  color: var(--ink-3);
  cursor: pointer;
}
.clear:hover { background: var(--stone-500); color: var(--ink); }
.go { flex: none; min-height: 38px; }
.go-ico { display: none; }

@media (max-width: 760px) {
  .go { min-width: 44px; padding: 0 12px; }
  /* ⚠️ 用 sr-only 而不是 display:none：
     display:none 会把「搜索」从无障碍树里一起删掉，按钮只剩一个没有名字的图标。 */
  .go-txt {
    position: absolute;
    width: 1px; height: 1px;
    padding: 0; margin: -1px;
    overflow: hidden;
    clip: rect(0 0 0 0);
    white-space: nowrap;
    border: 0;
  }
  .go-ico { display: block; }
  /* 手机上整条高一点，拇指更容易点中 */
  .search { padding-left: var(--sp-3); }
  .inp { min-height: 36px; }
}
</style>
