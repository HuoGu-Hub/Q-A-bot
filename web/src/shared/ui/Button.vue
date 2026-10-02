<script setup lang="ts">
/** 按钮。主按钮用灵火渐变 —— 一屏只该有一个主按钮。 */
withDefaults(defineProps<{
  variant?: 'primary' | 'ghost' | 'danger'
  size?: 'sm' | 'md'
  disabled?: boolean
  type?: 'button' | 'submit'
}>(), { variant: 'ghost', size: 'md', disabled: false, type: 'button' })
</script>

<template>
  <button
    :type="type"
    :disabled="disabled"
    class="btn"
    :class="[`v-${variant}`, `s-${size}`]"
  >
    <slot />
  </button>
</template>

<style scoped>
/**
 * 交互反馈的规矩：可点元素的悬浮态必须同时动【背景 + 边缘 + 位移】。
 *
 * 原来只改 border-color 和 color、背景不动，在深色底上几乎看不出差别 ——
 * 用户反馈"鼠标悬浮在按钮上效果不明显，无法分辨是否可交互"。
 * 只靠一道 1px 边框变色，在 --bg-shroud 这种深底上根本不够。
 *
 * 基础态压到 --bg-stone（比可点区域更"沉"），悬浮时抬到 --bg-raised。
 * 这一级色差是看得见的关键。
 */
.btn {
  font-family: var(--font-body);
  border-radius: var(--r-sm);
  border: 1px solid var(--edge);
  background: var(--bg-stone);
  color: var(--ink-dim);
  cursor: pointer;
  user-select: none;
  transition: background var(--dur-fast) var(--ease),
              border-color var(--dur-fast) var(--ease),
              color var(--dur-fast) var(--ease),
              box-shadow var(--dur-fast) var(--ease),
              transform var(--dur-fast) var(--ease);
}
.s-md { padding: 8px 18px; font-size: var(--fs-sm); }
.s-sm { padding: 5px 12px; font-size: var(--fs-xs); }

.btn:hover:not(:disabled) {
  background: var(--bg-raised);
  border-color: var(--edge-hover);
  color: var(--ink);
}
/* 按下：往回压一点，给"确实点到了"的触感 */
.btn:active:not(:disabled) { transform: translateY(1px); box-shadow: var(--shadow-sunken); }
.btn:disabled { opacity: .45; cursor: not-allowed; }

.v-primary {
  background: linear-gradient(180deg, var(--flame), var(--flame-deep));
  border-color: var(--flame-deep);
  color: var(--ink-on-flame);
  font-weight: 600;
  box-shadow: var(--shadow-flame);
}
.v-primary:hover:not(:disabled) {
  background: linear-gradient(180deg, var(--flame-bright), var(--flame));
  border-color: var(--flame);
  color: var(--ink-on-flame);
  box-shadow: 0 0 0 1px rgba(255, 196, 107, .3), 0 6px 22px var(--flame-glow);
}
.v-primary:active:not(:disabled) { box-shadow: var(--shadow-sunken); }

.v-danger { border-color: var(--edge); }
.v-danger:hover:not(:disabled) {
  border-color: var(--rust);
  color: var(--rust);
  background: var(--rust-veil);
}
</style>
