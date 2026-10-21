<script setup lang="ts">
/**
 * 居中弹窗 / 手机底部抽屉。
 *
 * 约定：
 *   - Teleport 到 body：固定定位的祖先只要带 transform 或 overflow，弹窗会被裁在面板里
 *   - role=dialog + aria-modal，Esc 关闭，点遮罩关闭
 *   - 打开时把焦点移进弹窗，关闭时还给原来那个元素
 *   - 打开期间锁 body 滚动
 *   - 窄屏（≤560px）自动贴底：手机上居中弹窗的手指覆盖面积太尴尬
 *
 * 内容超长时只有内容区滚动，标题栏与关闭按钮始终在。
 */
import { nextTick, onBeforeUnmount, ref, watch } from 'vue'
import Icon from './Icon.vue'

const props = withDefaults(defineProps<{
  open: boolean
  /** 标题栏文字；空则只显示关闭按钮，此时必须由调用方给 ariaLabel */
  title?: string
  ariaLabel?: string
  /** 弹窗宽度，默认 560px（窄屏自动满宽） */
  width?: string
}>(), { title: '', ariaLabel: '', width: '560px' })

const emit = defineEmits<{ close: [] }>()

const box = ref<HTMLElement | null>(null)
/** 打开前焦点在哪，关闭后还回去 */
let lastActive: HTMLElement | null = null

function onKey(e: KeyboardEvent) {
  if (e.key === 'Escape') {
    e.stopPropagation()
    emit('close')
  }
}

function release() {
  document.body.style.overflow = ''
  window.removeEventListener('keydown', onKey)
}

watch(() => props.open, async (open) => {
  if (open) {
    lastActive = document.activeElement instanceof HTMLElement ? document.activeElement : null
    document.body.style.overflow = 'hidden'
    window.addEventListener('keydown', onKey)
    await nextTick()
    box.value?.focus()
  } else {
    release()
    lastActive?.focus?.()
  }
})

// 组件被卸载时 el 已经没了，但监听器和 body 样式还挂着 —— 必须一起收掉
onBeforeUnmount(release)
</script>

<template>
  <Teleport to="body">
    <div v-if="open" class="layer">
      <div class="mask" @click="emit('close')" />
      <div
        ref="box"
        class="box"
        role="dialog"
        aria-modal="true"
        :aria-label="title || ariaLabel || undefined"
        tabindex="-1"
        :style="{ width }"
      >
        <!-- 手机上的一道把手：明确「这张纸可以往下推走」 -->
        <span class="grab" aria-hidden="true" />
        <header class="head">
          <span class="title">{{ title }}</span>
          <span class="head-spacer" />
          <div class="head-actions"><slot name="actions" /></div>
          <button type="button" class="x" aria-label="关闭" @click="emit('close')">
            <Icon name="close" :size="16" />
          </button>
        </header>
        <div class="body">
          <slot />
        </div>
      </div>
    </div>
  </Teleport>
</template>

<style scoped>
.layer {
  position: fixed;
  inset: 0;
  z-index: var(--z-drawer);
  display: flex;
  align-items: center;
  justify-content: center;
  padding: var(--sp-4);
}
.mask {
  position: absolute;
  inset: 0;
  background: var(--backdrop-mask);
  backdrop-filter: blur(3px);
  -webkit-backdrop-filter: blur(3px);
}
/* 弹窗本体：抬到 stone-400，靠 shadow-pop 说明它比页面高一层，不多描一圈重边 */
.box {
  position: relative;
  max-width: 100%;
  max-height: min(84svh, 760px);
  display: flex;
  flex-direction: column;
  background: var(--stone-400);
  border: 1px solid var(--edge);
  border-radius: var(--r-lg);
  box-shadow: var(--shadow-pop);
  /* tabindex=-1 的程序化焦点落点，不是可操作控件：
     弹窗自己的边框与投影已经表明焦点进来了，再套一圈键盘焦点环会像「整个弹窗是个按钮」 */
  outline: none;
}
.grab { display: none; }
.head {
  display: flex;
  align-items: center;
  gap: var(--sp-3);
  padding: var(--sp-3) var(--sp-4);
  border-bottom: 1px solid var(--hairline);
  min-height: 56px;
}
/* 标题与右侧动作之间的弹性空隙：#actions 才不会紧贴标题 */
.head-spacer { flex: 1; }
.title { font-size: var(--fs-lg); font-weight: var(--fw-semi); }
.head-actions { display: flex; align-items: center; gap: var(--sp-2); flex: none; }
.x {
  flex: none;
  display: grid;
  place-items: center;
  margin-left: auto;
  width: 36px;
  height: 36px;
  background: none;
  border: 1px solid transparent;
  border-radius: var(--r-sm);
  color: var(--ink-3);
  cursor: pointer;
  transition: background var(--dur-fast) var(--ease), border-color var(--dur-fast) var(--ease),
              color var(--dur-fast) var(--ease);
}
.x:hover { background: var(--stone-500); border-color: var(--edge); color: var(--ink); }
/* overscroll-behavior: contain —— 滚到两端时不要把滚动传给后面的页面 */
.body { padding: var(--sp-4); overflow: auto; overscroll-behavior: contain; }

@media (max-width: 560px) {
  /* 手机上贴底：手指够得到，也不会被键盘顶到一半 */
  .layer { align-items: flex-end; padding: 0; }
  .box {
    width: 100% !important;
    max-height: 88svh;
    border-radius: var(--r-lg) var(--r-lg) 0 0;
    border-bottom: 0;
    padding-bottom: var(--safe-b);
  }
  .grab {
    display: block;
    width: 40px;
    height: 4px;
    margin: 8px auto 0;
    border-radius: var(--r-pill);
    background: var(--edge-hover);
  }
  .head { padding-top: var(--sp-2); min-height: 48px; }
  .body { padding: var(--sp-4) var(--sp-4) var(--sp-5); }
}
</style>
