/**
 * 缓存策略表 —— 哪些接口可以缓存、能缓存多久。
 *
 * <p>默认不缓存。只有在这张表里命中正则的路径才会进入 `cache.ts`。
 * 新增接口忘了登记，行为就是「跟以前一样每次都请求」，不会出现"改了不生效"。
 *
 * <p>TTL 的依据只有一条：<b>这个数据晚给用户几十秒，会不会让人做出错误判断</b>。
 * 文案、目录、清单类给到分钟级；统计与实时读数只防连点。
 *
 * <p>身份、日志、进度、分页明细不登记 —— 它们"刚发生过什么"比"少发一次请求"重要。
 * 后台任何写操作成功后都会整端失效，所以登记进来也不会把改动盖掉。
 */
export interface CacheRule {
  /** 有效期，秒 */
  ttl: number
}

type Scope = 'public' | 'admin'

export type { Scope }

const PUBLIC: Array<[RegExp, CacheRule]> = [
  [/^\/site$/, { ttl: 300 }],
  [/^\/stats$/, { ttl: 60 }],
  [/^\/carousel$/, { ttl: 120 }],
  [/^\/kb\/groups$/, { ttl: 300 }],
  [/^\/kb\/page$/, { ttl: 300 }],
  [/^\/kb\/browse$/, { ttl: 60 }],
  [/^\/kb\/search$/, { ttl: 30 }],
  [/^\/map\/catalog$/, { ttl: 300 }],
  [/^\/map\/markers$/, { ttl: 120 }],
  [/^\/map\/resource-groups$/, { ttl: 300 }],
  [/^\/map\/resources$/, { ttl: 300 }],
  [/^\/map\/resource-areas$/, { ttl: 300 }],
]

const ADMIN: Array<[RegExp, CacheRule]> = [
  // 统计：一次进入发 8 个请求，短 TTL 就能削掉大部分重复访问
  [/^\/overview$/, { ttl: 10 }],
  [/^\/(keywords|misses|cosine|sources|visits)$/, { ttl: 10 }],
  [/^\/(traffic|llm)$/, { ttl: 5 }],

  // 配置与命令：回看频率最高的几页
  [/^\/settings$/, { ttl: 60 }],
  [/^\/pages$/, { ttl: 60 }],
  [/^\/carousel$/, { ttl: 30 }],
  [/^\/commands$/, { ttl: 60 }],
  [/^\/commands\/(variables|actions|policies)$/, { ttl: 60 }],
  [/^\/boards$/, { ttl: 60 }],
  [/^\/prompts$/, { ttl: 60 }],
  [/^\/system-aliases$/, { ttl: 60 }],
  [/^\/system-replies$/, { ttl: 60 }],
  [/^\/acl\/global$/, { ttl: 30 }],
  [/^\/(commands|boards)\/[^/]+\/acl$/, { ttl: 30 }],

  // 知识库
  [/^\/kb\/(terms|categories|blocks|blocks\/docs|blocks\/stats)$/, { ttl: 30 }],
  [/^\/kb\/categories\/entries$/, { ttl: 30 }],
  [/^\/kb\/wiki\/(sources|catalog)$/, { ttl: 60 }],

  // 蒸馏与语音
  [/^\/distill\/(settings|stats|blocklist)$/, { ttl: 30 }],
  [/^\/voice$/, { ttl: 60 }],
  [/^\/voice\/triggers$/, { ttl: 60 }],
  [/^\/voice\/tts\/voices$/, { ttl: 600 }],

  // 清单类
  [/^\/models$/, { ttl: 60 }],
  [/^\/map\/(catalog|maps|layers|markers)$/, { ttl: 60 }],
]

const TABLE: Record<Scope, Array<[RegExp, CacheRule]>> = { public: PUBLIC, admin: ADMIN }

/** 查表。path 是去掉前缀的接口路径，查询串不参与匹配但参与缓存键 */
export function ruleFor(scope: Scope, path: string): CacheRule | null {
  const bare = path.split('?')[0]
  for (const [re, rule] of TABLE[scope]) {
    if (re.test(bare)) return rule
  }
  return null
}
