<script setup lang="ts">
/**
 * 页面标题。
 *
 * 全站一个规格：26px 宋体 + 左侧一道余烬刻痕 + 下方一句说明。
 * 上一版资料库 38px、首页 clamp 到 68px、管理端干脆没有标题 ——
 * 页面之间「谁更重要」全靠放大字号猜。
 *
 * 右侧可选动作槽：一页最多一个主按钮，其余动作应该留在内容里。
 */
withDefaults(defineProps<{
  title: string
  desc?: string
}>(), { desc: '' })
</script>

<template>
  <header class="ph">
    <div class="ph-text">
      <h1 class="ph-title">{{ title }}</h1>
      <p v-if="desc || $slots.desc" class="ph-desc">
        <slot name="desc">{{ desc }}</slot>
      </p>
    </div>
    <div v-if="$slots.actions" class="ph-actions"><slot name="actions" /></div>
  </header>
</template>

<style scoped>
.ph {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: var(--sp-4);
  flex-wrap: wrap;
  margin: 0 0 var(--sp-5);
  padding-bottom: var(--sp-3);
  border-bottom: 1px solid var(--hairline);
}
.ph-text { min-width: 0; }
.ph-title {
  position: relative;
  font-size: var(--fs-2xl);
  letter-spacing: var(--tracking-ink);
  margin: 0;
  padding-left: 14px;
}
/* 余烬刻痕：页面标题的记号。长度跟着字高走，不做固定像素 */
.ph-title::before {
  content: '';
  position: absolute;
  left: 0;
  top: .18em;
  bottom: .18em;
  width: 3px;
  border-radius: 1px;
  background: var(--ember);
}
.ph-desc {
  margin: var(--sp-2) 0 0;
  font-size: var(--fs-sm);
  color: var(--ink-3);
  line-height: 1.75;
  max-width: 42em;
}
.ph-actions { display: flex; align-items: center; gap: var(--sp-2); flex: none; }

@media (max-width: 760px) {
  .ph { margin-bottom: var(--sp-4); align-items: flex-start; }
  .ph-desc { font-size: var(--fs-xs); }
}
</style>
