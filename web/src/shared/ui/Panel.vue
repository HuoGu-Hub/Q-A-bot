<script setup lang="ts">
/**
 * 面板 —— 基础容器。
 *
 * ⚠️ 视觉身份已改：**默认不描边框**，层级靠背景色差（表面阶梯）表达。
 * 原来每层都描 1px 边框，结果满屏线条框、所有东西一样重要。
 * 盒子本体样式在 `theme/texture.css` 的 `.panel`；这里只管标题区与折叠。
 *
 * 折叠：`collapsible` + `default-open`。默认收起时要给 `hint` 或 `count`，
 * 否则收起来只剩一个标题，用户不知道里面有什么。
 */
import { ref } from 'vue'

const props = withDefaults(
  defineProps<{
    title?: string
    /** 标题右侧的一句话说明 —— 收起状态下它仍然可见，所以很重要 */
    hint?: string
    /** 条目数徽标，收起时可见 */
    count?: number | string
    /** 四角铜饰。⚠️ 只在页面级外壳用一次，每个面板都挂会重新变成视觉噪音 */
    corners?: boolean
    stone?: boolean
    sunken?: boolean
    /** 灵火光晕。⚠️ 与 corners 争用 ::after，别同时开 */
    glow?: boolean
    collapsible?: boolean
    defaultOpen?: boolean
  }>(),
  {
    corners: false,
    stone: false,
    sunken: false,
    glow: false,
    collapsible: false,
    defaultOpen: true,
  }
)

const open = ref(props.collapsible ? props.defaultOpen : true)
function toggle() {
  if (props.collapsible) open.value = !open.value
}
</script>

<template>
  <section
    class="panel texture-noise"
    :class="[
      corners && !sunken ? 'panel--corners' : '',
      stone ? 'panel--stone' : '',
      sunken ? 'panel--sunken' : '',
      glow ? 'glow-flame' : '',
      collapsible ? 'panel--collapsible' : '',
      collapsible && !open ? 'panel--closed' : '',
    ]"
  >
    <h2 v-if="title" class="panel-title">
      <button
        v-if="collapsible"
        type="button"
        class="head head--btn"
        :aria-expanded="open"
        @click="toggle"
      >
        <span class="chev" :class="{ 'chev--open': open }" aria-hidden="true">▸</span>
        <span class="head-text">{{ title }}</span>
        <span v-if="hint" class="head-hint">{{ hint }}</span>
        <span v-if="count !== undefined && count !== ''" class="head-count num">{{ count }}</span>
      </button>
      <div v-else class="head">
        <span class="head-text">{{ title }}</span>
        <span v-if="hint" class="head-hint">{{ hint }}</span>
        <span v-if="count !== undefined && count !== ''" class="head-count num">{{ count }}</span>
      </div>
      <slot name="actions" />
    </h2>
    <div v-show="open" class="panel-body">
      <slot />
    </div>
  </section>
</template>

<style scoped>
.panel-title {
  font-family: var(--font-body);
  /* 16px：原来这里是 14px，和正文一样大 —— 标题不成为标题是「满屏线条框」
     观感的根源之一。层级由字号 + 字重 + 字距共同决定。 */
  font-size: var(--fs-section);
  font-weight: var(--fw-semi);
  letter-spacing: .01em;
  margin: 0;
  display: flex;
  align-items: center;
  gap: var(--sp-3);
}
/* 标题与内容之间的间距，由「谁在下面」决定 ——
   不要用 :has 去猜 body 是否隐藏，直接用状态类。 */
.panel-title { margin-bottom: var(--sp-4); }
.panel--collapsible .panel-title { margin-bottom: 0; }
.panel--collapsible .panel-body { margin-top: var(--sp-4); }

/* 收起时收窄内边距：展开态用 24px 是为了给内容呼吸，但收起的行只有一行字，
   沿用 24px 会让 12 个分组白白占掉一屏（实测每行 ~86px → 收到 ~56px）。 */
.panel--closed { padding-top: var(--sp-3); padding-bottom: var(--sp-3); }

.head {
  display: flex;
  align-items: center;
  gap: var(--sp-3);
  flex: 1;
  min-width: 0;
}
/* 可点区域铺满标题行，别只让文字可点 */
.head--btn {
  background: none;
  border: 0;
  padding: var(--sp-2) var(--sp-2);
  margin: calc(var(--sp-2) * -1) calc(var(--sp-2) * -1);
  font: inherit;
  color: inherit;
  cursor: pointer;
  text-align: left;
  border-radius: var(--r-sm);
  transition: background var(--dur-fast) var(--ease);
}
/* 悬浮必须看得见"这一整行都能点" —— 只改文字颜色在深色底上不够 */
.head--btn:hover {
  background: var(--surface-hover);
  box-shadow: inset 0 0 0 1px var(--edge-soft);
}
.head--btn:hover .head-text { color: var(--flame-bright); }
.head--btn:active { background: var(--surface-active); }
.head--btn:focus-visible { outline: 2px solid var(--flame); outline-offset: 3px; }

/* 灵火刻痕：区块标题的标识。全文只给「当前最重要的一处」用。 */
.head-text {
  position: relative;
  padding-left: var(--sp-4);
  white-space: nowrap;
  transition: color var(--dur-fast) var(--ease);
}
.head-text::before {
  content: '';
  position: absolute;
  left: 0;
  top: 50%;
  transform: translateY(-50%);
  width: 3px;
  height: 14px;
  border-radius: 1px;
  background: linear-gradient(180deg, var(--flame-bright), var(--flame-deep));
  box-shadow: 0 0 8px var(--flame-glow);
}

.chev {
  flex: none;
  width: 14px;
  color: var(--copper);
  font-size: 13px;
  line-height: 1;
  transition: transform var(--dur) var(--ease), color var(--dur-fast) var(--ease);
}
.chev--open { transform: rotate(90deg); color: var(--flame); }
.head--btn:hover .chev { color: var(--flame); }

.head-hint {
  font-size: var(--fs-meta);
  font-weight: var(--fw-normal);
  color: var(--ink-faint);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.head-count {
  flex: none;
  margin-left: auto;
  font-size: var(--fs-meta);
  font-weight: var(--fw-normal);
  color: var(--ink-dim);
  background: var(--surface-inset);
  border-radius: var(--r-pill);
  padding: 1px 9px;
}
</style>
