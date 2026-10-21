<script setup lang="ts">
/**
 * 管理端手机导航抽屉。
 *
 * 上一版把 7 个 tab 横排一行，390px 下「页面信息」和「退出」被挤到屏幕外，
 * 而且没有任何滚动提示 —— 等于导航只剩 5 项。
 *
 * 抽屉把 7 个目的地竖着列出来，每项一行、带图标和一句说明，
 * 顺手把主题开关与退出放在底部。
 */
import { onBeforeUnmount, watch } from 'vue'
import { RouterLink } from 'vue-router'
import Icon from './Icon.vue'
import ThemeToggle from './ThemeToggle.vue'

const props = withDefaults(defineProps<{
  open: boolean
  items: Array<{ to: string; label: string; icon: string; desc?: string; active: boolean }>
}>(), {})

const emit = defineEmits<{ close: []; logout: [] }>()

function onKey(e: KeyboardEvent) {
  if (e.key === 'Escape') emit('close')
}

watch(() => props.open, (open) => {
  document.body.style.overflow = open ? 'hidden' : ''
  if (open) window.addEventListener('keydown', onKey)
  else window.removeEventListener('keydown', onKey)
})

onBeforeUnmount(() => {
  document.body.style.overflow = ''
  window.removeEventListener('keydown', onKey)
})
</script>

<template>
  <Teleport to="body">
    <div v-if="open" class="layer">
      <div class="mask" @click="emit('close')" />
      <div class="panel" role="dialog" aria-modal="true" aria-label="管理导航">
        <header class="head">
          <span class="brand">
            <Icon name="flame" :size="17" class="mark" />
            飘雪喵 · 管理
          </span>
          <div class="head-right">
            <ThemeToggle />
            <button type="button" class="x" aria-label="关闭菜单" @click="emit('close')">
              <Icon name="close" :size="16" />
            </button>
          </div>
        </header>

        <nav class="list" aria-label="后台导航">
          <RouterLink
            v-for="n in items"
            :key="n.to"
            :to="n.to"
            class="item"
            :class="{ active: n.active }"
            :aria-current="n.active ? 'page' : undefined"
            @click="emit('close')"
          >
            <span class="ico" aria-hidden="true"><Icon :name="n.icon" :size="18" /></span>
            <span class="txt">
              <span class="l">{{ n.label }}</span>
              <span v-if="n.desc" class="d">{{ n.desc }}</span>
            </span>
            <Icon name="chevron-right" :size="15" class="go" />
          </RouterLink>
        </nav>

        <footer class="foot">
          <button type="button" class="out" @click="emit('logout'); emit('close')">
            <Icon name="lock" :size="15" /> 退出登录
          </button>
        </footer>
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
  justify-content: flex-end;
}
.mask {
  position: absolute;
  inset: 0;
  background: var(--backdrop-mask);
  backdrop-filter: blur(3px);
  -webkit-backdrop-filter: blur(3px);
}
/* 从右侧滑出：手机右手拇指够得着，也不会把整页盖满 */
.panel {
  position: relative;
  width: min(320px, 86vw);
  height: 100%;
  display: flex;
  flex-direction: column;
  background: var(--stone-300);
  border-left: 1px solid var(--edge);
  box-shadow: var(--shadow-pop);
  padding-bottom: var(--safe-b);
  animation: slide .2s var(--ease-out);
}
@keyframes slide { from { transform: translateX(16px); opacity: .6; } to { transform: none; opacity: 1; } }

.head {
  flex: none;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--sp-2);
  padding: var(--sp-3) var(--sp-3) var(--sp-3) var(--sp-4);
  border-bottom: 1px solid var(--hairline);
  /* 刘海屏：抽屉从屏幕顶开始，标题要让开状态栏 */
  padding-top: calc(var(--sp-3) + var(--safe-t));
}
.brand {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  font-family: var(--font-title);
  font-size: var(--fs-base);
  font-weight: var(--fw-semi);
  letter-spacing: .04em;
  color: var(--ink);
  white-space: nowrap;
}
.mark { color: var(--ember); }
.head-right { display: flex; align-items: center; gap: var(--sp-2); }
.x {
  display: grid;
  place-items: center;
  width: 36px;
  height: 36px;
  border: 1px solid var(--edge);
  border-radius: var(--r-sm);
  background: var(--stone-400);
  color: var(--ink-2);
  cursor: pointer;
}

.list { flex: 1; overflow-y: auto; padding: var(--sp-2); }
.item {
  display: flex;
  align-items: center;
  gap: var(--sp-3);
  min-height: 56px;
  padding: var(--sp-2) var(--sp-3);
  border-radius: var(--r-sm);
  text-decoration: none;
  color: var(--ink-2);
}
.item:hover { background: var(--surface-hover); }
.item.active { background: var(--ember-veil); color: var(--ember-hot); }
.ico {
  flex: none;
  display: grid;
  place-items: center;
  width: 34px;
  height: 34px;
  border-radius: var(--r-sm);
  background: var(--stone-void);
  box-shadow: var(--bevel-inset);
  color: var(--ink-3);
}
.item.active .ico { color: var(--ember); }
.txt { flex: 1; min-width: 0; display: flex; flex-direction: column; gap: 1px; }
.l { font-size: var(--fs-base); }
.d { font-size: var(--fs-xs); color: var(--ink-3); }
.go { flex: none; color: var(--ink-4); }

.foot { flex: none; padding: var(--sp-3); border-top: 1px solid var(--hairline); }
.out {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  width: 100%;
  min-height: 44px;
  border: 1px solid var(--edge);
  border-radius: var(--r-sm);
  background: none;
  color: var(--ink-3);
  font-size: var(--fs-sm);
  cursor: pointer;
}
.out:hover { border-color: var(--blight); color: var(--blight-lift); background: var(--blight-veil); }
</style>
