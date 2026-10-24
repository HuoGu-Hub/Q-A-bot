import type { CommandAcl } from '@shared/api/types'

/**
 * 名单表单 —— 指令、板块、全局名单共用同一份形状。
 *
 * <p>四个输入框都按"一行一个"收号码，提交时再拆成
 * {@code {white: [...], black: [...]}}。抽出来是为了让两处编辑器
 * 不会各自长出一套解析规则。
 */
export interface AclForm {
  whiteUsers: string
  whiteGroups: string
  blackUsers: string
  blackGroups: string
}

export interface AclEntryBody {
  type: 'uid' | 'gid'
  value: number
}

export interface AclBody {
  white: AclEntryBody[]
  black: AclEntryBody[]
}

export function emptyAclForm(): AclForm {
  return { whiteUsers: '', whiteGroups: '', blackUsers: '', blackGroups: '' }
}

export function parseIds(text: string): number[] {
  return text.split(/[\s,，、]+/).map((s) => s.trim()).filter(Boolean)
    .map(Number).filter((n) => Number.isFinite(n) && n > 0)
}

export function aclBody(form: AclForm): AclBody {
  return {
    white: [
      ...parseIds(form.whiteUsers).map((v) => ({ type: 'uid' as const, value: v })),
      ...parseIds(form.whiteGroups).map((v) => ({ type: 'gid' as const, value: v })),
    ],
    black: [
      ...parseIds(form.blackUsers).map((v) => ({ type: 'uid' as const, value: v })),
      ...parseIds(form.blackGroups).map((v) => ({ type: 'gid' as const, value: v })),
    ],
  }
}

export function fillAcl(target: AclForm, source: CommandAcl): void {
  target.whiteUsers = source.white.filter((a) => a.type === 'uid').map((a) => a.value).join('\n')
  target.whiteGroups = source.white.filter((a) => a.type === 'gid').map((a) => a.value).join('\n')
  target.blackUsers = source.black.filter((a) => a.type === 'uid').map((a) => a.value).join('\n')
  target.blackGroups = source.black.filter((a) => a.type === 'gid').map((a) => a.value).join('\n')
}

/** 折叠标题上的读数：几条号码，空就是「空」 */
export function aclCount(form: AclForm): string {
  const n = parseIds(form.whiteUsers).length + parseIds(form.whiteGroups).length
    + parseIds(form.blackUsers).length + parseIds(form.blackGroups).length
  return n ? n + ' 条' : '空'
}
