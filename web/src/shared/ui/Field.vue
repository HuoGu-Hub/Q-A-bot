<script setup lang="ts">
/**
 * 表单行：左侧「标签 + 说明」，右侧控件。
 *
 * ⚠️ 根元素是 label 而不是 div：读屏念到输入框时才会一起念出「提问量」「检索阈值」。
 * 用 label 把控件包进来就有了隐式关联，不需要给每个调用点的控件编 id。
 * 子元素一律用 span：label 的内容模型只允许短语内容，里面塞 div 是无效 HTML。
 */
withDefaults(
  defineProps<{
    label: string
    /** 一句话说明这个配置是干什么的 */
    hint?: string
    /** 已改动 —— 高亮但不位移 */
    changed?: boolean
    /** 控件列宽。默认 340px；开关类可以窄一点 */
    controlWidth?: string
  }>(),
  { changed: false, controlWidth: '340px' }
)
</script>

<template>
  <label class="field" :class="{ changed }">
    <span class="meta">
      <span class="label">
        {{ label }}
        <span v-if="changed" class="dot" role="img" aria-label="已修改" />
      </span>
      <span v-if="hint" class="hint">{{ hint }}</span>
    </span>
    <span class="ctrl">
      <slot />
    </span>
  </label>
</template>

<style scoped>
/* 同级之间用发丝线分隔，而不是给每一行再套一个框 */
.field + .field { border-top: 1px solid var(--hairline); }

.field {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(0, v-bind(controlWidth));
  gap: var(--sp-4);
  align-items: start;
  padding: var(--sp-3);
  border-radius: var(--r-sm);
  transition: background var(--dur-fast) var(--ease);
}
.field:first-child { padding-top: var(--sp-1); }
.field:last-child { padding-bottom: var(--sp-1); }

.field.changed {
  background: var(--ember-veil);
  box-shadow: inset 2px 0 0 var(--ember);
}

.meta { min-width: 0; }
/* 根元素是 label，里面的 .label / .hint 必须是 span，于是默认是行内元素 ——
   这里显式恢复成块级，否则标签和说明会挤在同一行。 */
.label { display: block; font-size: var(--fs-base); color: var(--ink); }
/* 改动标记用 6px 圆点：字符的基线和大小随字体变，会忽大忽小忽高忽低 */
.dot {
  display: inline-block;
  width: 6px;
  height: 6px;
  margin-left: 6px;
  border-radius: 50%;
  background: var(--ember);
  vertical-align: 2px;
}
.hint { display: block; font-size: var(--fs-xs); color: var(--ink-3); margin-top: 2px; line-height: 1.65; }
.ctrl { display: block; min-width: 0; }

@media (max-width: 760px) {
  .field { grid-template-columns: minmax(0, 1fr); gap: var(--sp-2); padding: var(--sp-3) 0; }
  .field + .field { border-top: 1px solid var(--hairline); }
}
</style>
