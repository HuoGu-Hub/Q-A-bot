<script setup lang="ts">
/**
 * 表单行：左侧「标签 + 说明」，右侧控件。
 *
 * 抽它的原因：这个结构在设置页、分类页、模型页、知识库页各写了一遍，
 * 而且都用了「负 margin 贴边 + 2px 火焰左边线」来表示"已改动"——
 * 副作用是改动行会整体左移 12px，行宽随"是否改动"跳动。
 * 这里改成背景 + 内阴影，不动布局。
 *
 * ⚠️ 根元素是 **<label>** 而不是 <div>。
 * 上一版视觉上看着像标签（左边一行字、右边一个控件），但两者在无障碍树里
 * 毫无关系：读屏念到输入框只会说"编辑框"，不说这是"提问量"还是"检索阈值"。
 * 用 <label> 把控件包进来就有了隐式关联，**不需要**去给每个调用点的控件编 id。
 * 代价是标签文字与说明文字都会进入控件的可访问名（这反而是好事：说明本来就在
 * 解释这个字段该填什么）。子元素一律用 <span>：<label> 的内容模型只允许短语内容，
 * 里面塞 <div> 是无效 HTML —— 布局靠 display:grid/block 就行。
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
/* 同级之间用发丝线分隔，而不是给每一行再套一个框。
   线要看得见；但"两两能不能一眼分开"主要靠上下留白。 */
.field + .field { border-top: 1px solid var(--hairline); }

.field {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(0, v-bind(controlWidth));
  gap: var(--sp-4);
  align-items: start;
  padding: var(--sp-3) var(--sp-3);
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
/* ⚠️ 根元素从 <div> 换成 <label> 之后，里面的 .label / .hint 也必须是 <span>
   （label 的内容模型只允许短语内容），于是它们默认成了**行内**元素 ——
   标签和说明会挤在同一行上（"启用首页轮播关掉后公开站首页不显示轮播…"）。
   这里显式恢复成块级，版面与改造前完全一致。 */
.label { display: block; font-size: var(--fs-base); color: var(--ink); }
/* 改动标记从"●"字符换成 6px 的圆点：字符的基线和大小随字体变，
   并排看会跟着系统字体忽大忽小忽高忽低。 */
.dot {
  display: inline-block;
  width: 6px;
  height: 6px;
  margin-left: 6px;
  border-radius: 50%;
  background: var(--ember);
  vertical-align: 2px;
}
.hint { display: block; font-size: var(--fs-xs); color: var(--ink-3); margin-top: 3px; line-height: 1.65; }
.ctrl { display: block; min-width: 0; }

@media (max-width: 760px) {
  .field { grid-template-columns: minmax(0, 1fr); gap: var(--sp-2); }
}
</style>
