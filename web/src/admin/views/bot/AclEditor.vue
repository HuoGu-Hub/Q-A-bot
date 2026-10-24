<script setup lang="ts">
/**
 * 名单编辑器 —— 四个输入框，指令与板块共用。
 *
 * <p>只管形状，不负责读写：父组件拿 {@link AclForm} 去拼请求，
 * 这样"哪些档位需要名单"的规则仍然留在编辑器里。
 */
import Input from '@shared/ui/Input.vue'
import type { AclForm } from './aclForm'

const props = defineProps<{ modelValue: AclForm }>()
const emit = defineEmits<{ 'update:modelValue': [AclForm] }>()

function patch(key: keyof AclForm, value: string) {
  emit('update:modelValue', { ...props.modelValue, [key]: value })
}
</script>

<template>
  <div class="acl-grid">
    <div>
      <div class="acl-k good">白名单 · QQ</div>
      <Input
        :model-value="modelValue.whiteUsers"
        multiline
        :rows="2"
        placeholder="一行一个"
        aria-label="白名单 QQ 号"
        @update:model-value="patch('whiteUsers', $event)"
      />
    </div>
    <div>
      <div class="acl-k good">白名单 · 群</div>
      <Input
        :model-value="modelValue.whiteGroups"
        multiline
        :rows="2"
        placeholder="整群"
        aria-label="白名单群号"
        @update:model-value="patch('whiteGroups', $event)"
      />
    </div>
    <div>
      <div class="acl-k bad">黑名单 · QQ</div>
      <Input
        :model-value="modelValue.blackUsers"
        multiline
        :rows="2"
        placeholder="一行一个"
        aria-label="黑名单 QQ 号"
        @update:model-value="patch('blackUsers', $event)"
      />
    </div>
    <div>
      <div class="acl-k bad">黑名单 · 群</div>
      <Input
        :model-value="modelValue.blackGroups"
        multiline
        :rows="2"
        placeholder="整个群"
        aria-label="黑名单群号"
        @update:model-value="patch('blackGroups', $event)"
      />
    </div>
  </div>
</template>

<style scoped>
.acl-grid { display: grid; grid-template-columns: 1fr 1fr; gap: var(--sp-3); }
.acl-k { margin-bottom: 5px; font-size: var(--fs-xs); letter-spacing: .04em; }
.acl-k.good { color: var(--vital); }
.acl-k.bad { color: var(--blight-lift); }
@media (max-width: 900px) {
  .acl-grid { grid-template-columns: 1fr; }
}
</style>
