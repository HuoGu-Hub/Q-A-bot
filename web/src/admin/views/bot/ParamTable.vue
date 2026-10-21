<script setup lang="ts">
/**
 * 参数表编辑器 —— 指令与板块共用。
 *
 * <p>它只认 {@code AdminParamSpec[]}，不关心调用方是"指令"还是"板块"。
 * 每次改动都发一份新数组：父组件用 {@code v-model:params} 或
 * {@code @update:params} 接住，避免子组件直接改 props。
 */
import type { AdminParamSpec } from '@shared/api/types'
import Input from '@shared/ui/Input.vue'
import Select from '@shared/ui/Select.vue'
import Button from '@shared/ui/Button.vue'
import { PARAM_TYPES } from './commandOptions'

const props = withDefaults(defineProps<{
  params: AdminParamSpec[]
  emptyHint?: string
}>(), { emptyHint: '' })

const emit = defineEmits<{ 'update:params': [AdminParamSpec[]] }>()

function setParams(list: AdminParamSpec[]) {
  emit('update:params', list)
}

function patchParam(i: number, patch: Partial<AdminParamSpec>) {
  const list = props.params.slice()
  list[i] = { ...list[i], ...patch }
  setParams(list)
}

function addParam() {
  setParams([...props.params, {
    name: '', type: 'text', required: false, default: '', maxChars: 200,
    example: '', description: '', multiple: false,
  }])
}

function removeParam(i: number) {
  setParams(props.params.filter((_, idx) => idx !== i))
}

defineExpose({ addParam })

/** checkbox 的 change 事件要读 target.checked，模板里直接转型不好读，收成一个函数 */
function onRequiredChange(i: number, e: Event) {
  patchParam(i, { required: (e.target as HTMLInputElement).checked })
}

function onMultipleChange(i: number, e: Event) {
  patchParam(i, { multiple: (e.target as HTMLInputElement).checked })
}

/** enum 的候选值在表单里是逗号分隔的文本 */
function optionsText(p: AdminParamSpec) {
  return (p.options ?? []).join(', ')
}

function onOptionsChange(i: number, v: string) {
  patchParam(i, { options: v.split(/[,，]/).map((s) => s.trim()).filter(Boolean) })
}
</script>

<template>
  <div class="param-table">
    <p v-if="!params.length && emptyHint" class="faint note">{{ emptyHint }}</p>
    <div class="params">
      <div v-if="params.length" class="param-head" aria-hidden="true">
        <span>参数名</span><span>类型</span><span>必填</span>
        <span>示例</span><span>说明</span><span>多值</span><span></span>
      </div>
      <div v-for="(p, i) in params" :key="i" class="param-row">
        <Input
          :model-value="p.name"
          placeholder="名字"
          aria-label="参数名"
          @update:model-value="patchParam(i, { name: $event })"
        />
        <Select
          :model-value="p.type"
          :options="PARAM_TYPES"
          aria-label="参数类型"
          @update:model-value="patchParam(i, { type: $event })"
        />
        <label class="req">
          <input type="checkbox" :checked="p.required" @change="onRequiredChange(i, $event)" />
          必填
        </label>
        <Input
          :model-value="p.example ?? ''"
          placeholder="示例"
          aria-label="示例"
          @update:model-value="patchParam(i, { example: $event })"
        />
        <Input
          :model-value="p.description ?? ''"
          placeholder="说明"
          aria-label="说明"
          @update:model-value="patchParam(i, { description: $event })"
        />
        <label class="req">
          <input type="checkbox" :checked="p.multiple" @change="onMultipleChange(i, $event)" />
          可多值
        </label>
        <Button size="sm" variant="danger" @click="removeParam(i)">移除</Button>
        <Input
          v-if="p.type === 'enum'"
          class="enum-opts"
          :model-value="optionsText(p)"
          placeholder="候选值，逗号分开"
          aria-label="候选值"
          @update:model-value="onOptionsChange(i, $event)"
        />
      </div>
    </div>
    <div v-if="$slots.actions" class="param-actions">
      <slot name="actions" />
    </div>
  </div>
</template>

<style scoped>
.params { display: flex; flex-direction: column; gap: var(--sp-2); }
.param-head {
  display: grid;
  grid-template-columns: 128px 104px 58px 112px minmax(180px, 1fr) 74px auto;
  gap: var(--sp-2);
  padding: 0 2px;
  color: var(--ink-3);
  font-size: 11px;
  letter-spacing: .1em;
}
.param-row {
  display: grid;
  /* 说明是唯一需要吃剩余宽度的列；示例、类型、必填都够用就好 */
  grid-template-columns: 128px 104px 58px 112px minmax(180px, 1fr) 74px auto;
  gap: var(--sp-2);
  align-items: center;
}
.param-row .enum-opts { grid-column: 1 / -1; }
.req {
  display: flex; align-items: center; gap: 6px;
  font-size: var(--fs-meta); color: var(--mist); white-space: nowrap;
}
.req input[type='checkbox'] { accent-color: var(--flame); }
.param-actions { display: flex; gap: var(--sp-2); margin-top: var(--sp-2); }
.note { font-size: var(--fs-meta); margin: 0 0 var(--sp-2); }
@media (max-width: 1100px) {
  .param-head { display: none; }
  .param-row { grid-template-columns: 1fr 1fr; }
  .param-row .enum-opts { grid-column: 1 / -1; }
}
</style>
