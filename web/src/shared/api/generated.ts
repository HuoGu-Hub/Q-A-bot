/**
 * 生成类型的唯一入口。
 *
 * <p>schema.d.ts 由 pnpm gen:api 从 docs/openapi.json 生成，不提交；
 * 这里把 components["schemas"] 提成短名字，手写类型只引用它，不再抄字段。
 */
import type { components } from './schema'

export type Schemas = components['schemas']
