export interface CacheRule {
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
  [/^\/overview$/, { ttl: 10 }],
  [/^\/(keywords|misses|cosine|sources|visits)$/, { ttl: 10 }],
  [/^\/(traffic|llm)$/, { ttl: 5 }],

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

  [/^\/kb\/(terms|categories|blocks|blocks\/docs|blocks\/stats)$/, { ttl: 30 }],
  [/^\/kb\/categories\/entries$/, { ttl: 30 }],
  [/^\/kb\/wiki\/(sources|catalog)$/, { ttl: 60 }],

  [/^\/distill\/(settings|stats|blocklist)$/, { ttl: 30 }],
  [/^\/voice$/, { ttl: 60 }],
  [/^\/voice\/triggers$/, { ttl: 60 }],
  [/^\/voice\/tts\/voices$/, { ttl: 600 }],

  [/^\/map\/status$/, { ttl: 10 }],
  [/^\/map\/anchor$/, { ttl: 30 }],

  [/^\/models$/, { ttl: 60 }],
  [/^\/map\/(catalog|maps|layers|markers)$/, { ttl: 60 }],
]

const TABLE: Record<Scope, Array<[RegExp, CacheRule]>> = { public: PUBLIC, admin: ADMIN }

export function ruleFor(scope: Scope, path: string): CacheRule | null {
  const bare = path.split('?')[0]
  for (const [re, rule] of TABLE[scope]) {
    if (re.test(bare)) return rule
  }
  return null
}
