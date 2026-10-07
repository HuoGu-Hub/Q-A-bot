import { defineConfig, loadEnv, type Plugin } from 'vite'
import vue from '@vitejs/plugin-vue'
import { fileURLToPath, URL } from 'node:url'
import net from 'node:net'

const here = (p: string) => fileURLToPath(new URL(p, import.meta.url))

/**
 * 双端多页构建。
 *
 * 产物：
 *   dist/public.html + dist/admin.html  （两个入口，共用 assets/）
 *
 * 开发期把 /api 和 /admin/api 代理到后端，避免跨域。
 */

/**
 * 后端地址。
 *
 * ⚠️ 默认 127.0.0.1:8080 在【直接跑在宿主机】时是对的。
 * 但如果前端在容器里、后端在宿主机上（开发环境常见），
 * 127.0.0.1 指向容器自己 → 代理会 ECONNREFUSED。
 * 这时用环境变量覆盖：
 *
 *   DSH 容器内连宿主机后端：
 *     API_TARGET=http://$(getent ahostsv4 host.docker.internal | awk '{print $1}' | head -n1):8080 pnpm dev
 */
const API_TARGET = process.env.API_TARGET ?? 'http://127.0.0.1:8080'

/**
 * 允许的 Host —— **穿透 / 反向代理必须配置**，否则用域名访问会被 Vite 挡掉：
 *
 *   Blocked request. This host ("example.com") is not allowed.
 *   To allow this host, add "example.com" to `server.allowedHosts` in vite.config.js.
 *
 * 这不是 bug，是 Vite 的 **DNS rebinding 防护**（默认只认 localhost / 127.0.0.1）。
 * 前导点 `.` 表示「该域名本身 + 任意子域」—— 隧道服务给的子域名会变，
 * 用点前缀就不用每次改代码。
 *
 * ⚠️ 域名**不写死在这个文件里**（仓库是公开的）——统一放仓库根的 .env，
 *    和后端共用同一个 env 文件；也可以临时用命令行覆盖：
 *   ALLOWED_HOSTS=a.com,b.com pnpm dev
 */
const ROOT_ENV = loadEnv('development', fileURLToPath(new URL('..', import.meta.url)), 'ALLOWED_HOSTS')

const TUNNEL_HOSTS = (process.env.ALLOWED_HOSTS ?? ROOT_ENV.ALLOWED_HOSTS ?? '')
  .split(',')
  .map((s) => s.trim())
  .filter(Boolean)

/**
 * 是否用「轮询」监听文件变化。默认开。
 *
 * 为什么需要：从 DSH 容器里改的文件，经 Docker Desktop 挂载写回 Windows 时
 * **不会给宿主机的 Vite 发文件变更事件**。表现就是两个：
 *   1. 改完前端代码页面不热更新，手动刷新才生效；
 *   2. 连 vite.config.ts 自己改了也不会自动重启（所以上面 allowedHosts 改完必须手动重启一次）。
 * 开轮询后 Vite 主动去比对时间戳，两个问题一起消失；代价是持续的少量 CPU。
 *
 * 关掉：WATCH_POLLING=0 pnpm dev
 */
const USE_POLLING = process.env.WATCH_POLLING !== '0'

/**
 * 开发服务器的「多页 + SPA」适配插件。
 *
 * 解决两个问题（生产模式由后端 SpaForwardController 处理，开发模式没人管）：
 *
 * 1. **入口重定向**：直接访问 /public.html 时，地址栏停在 /public.html，
 *    而路由表里没有这个路径 → 落到 404 兜底页。
 *    这里把它 302 到 /，让路由正常匹配。
 *
 * 2. **SPA fallback**：刷新 /library、/plaza 这类前端路由时，
 *    Vite 找不到对应文件会返回 404。这里改写到对应的入口 html。
 *
 * ⚠️ 两个入口要分开判断：
 *    /admin/**  → admin.html（管理后台 base 是 /admin/）
 *    其余       → public.html
 */
/**
 * 这些前缀是**后端接口**，SPA fallback 必须放行（交给下面的 proxy）。
 *
 * ⚠️ 必须包含 `/admin/api`：它虽然是 `/admin` 开头，但那是后端接口而不是
 * 前端路由。漏掉它 = 开发期所有后台接口都被改写成 admin.html，前端报
 * 「Unexpected token '<', "<!DOCTYPE "... is not valid JSON」——
 * 而且这个错误看起来像后端挂了，实际是前端 dev server 自己吞掉了请求。
 * （后端 SpaForwardController 里有一份同样的前缀表，两边要保持一致。）
 */
const BACKEND_PREFIXES = ['/api', '/admin/api', '/onebot', '/actuator']
const isBackendCall = (p: string) =>
  BACKEND_PREFIXES.some((x) => p === x || p.startsWith(x + '/'))

function spaFallback(): Plugin {
  return {
    name: 'qqbot-spa-fallback',
    configureServer(server) {
      server.middlewares.use((req, res, next) => {
        const url = req.url ?? '/'
        const path = url.split('?')[0]

        // ① 入口重定向：/public.html → /，/admin.html → /admin/
        if (path === '/public.html') {
          res.writeHead(302, { Location: '/' + (url.includes('?') ? url.slice(url.indexOf('?')) : '') })
          res.end()
          return
        }
        if (path === '/admin.html') {
          res.writeHead(302, { Location: '/admin/' })
          res.end()
          return
        }

        // ② 带扩展名的静态资源、后端接口、Vite 内部路径，一律不干预
        if (/\.[a-zA-Z0-9]+$/.test(path) || isBackendCall(path) ||
            path.startsWith('/@') || path.startsWith('/node_modules') ||
            path.startsWith('/src')) {
          next()
          return
        }

        // ③ SPA fallback
        req.url = path.startsWith('/admin') ? '/admin.html' : '/public.html'
        next()
      })
    },
  }
}
/**
 * 开发期把「代理目标连不上」直接喊出来。
 *
 * 踩过的坑：后端实际跑在 8094，而代理默认指向 8080 —— 页面报的却是一句
 * 「管理后台未启用：请在 .env 里设置 ADMIN_PASSWORD」，让人以为是密码问题，
 * 实际是代理指错了端口。这里在 dev server 起来时探一次，指错了立刻提示。
 */
function apiTargetGuard(): Plugin {
  return {
    name: 'qqbot-api-target-guard',
    configureServer(server) {
      server.httpServer?.once('listening', () => {
        const url = new URL(API_TARGET)
        const socket = net.connect({ host: url.hostname, port: Number(url.port || 80) })
        socket.setTimeout(2000)
        socket.on('connect', () => {
          console.log(`\n  ✓ /api、/admin/api 代理目标可达：${API_TARGET}\n`)
          socket.end()
        })
        socket.on('timeout', () => socket.destroy())
        socket.on('error', () => {
          console.warn(`\n  ✗ 代理目标 ${API_TARGET} 连不上！`)
          console.warn('    页面这时报的错（比如「管理后台未启用」）多半是假象 —— 真正原因是后端没起或端口不对。')
          console.warn('    指定后端：API_TARGET=http://<主机>:<端口> pnpm dev\n')
        })
      })
    },
  }
}
export default defineConfig({
  plugins: [vue(), spaFallback(), apiTargetGuard()],
  resolve: {
    alias: {
      '@': here('./src'),
      '@shared': here('./src/shared'),
      '@theme': here('./src/theme'),
    },
  },
  build: {
    outDir: 'dist',
    emptyOutDir: true,
    rollupOptions: {
      input: {
        public: here('./public.html'),
        admin: here('./admin.html'),
      },
    },
  },
  server: {
    host: '0.0.0.0',
    port: 5173,
    /**
     * ⚠️ Vite 的 dev server 是**开发工具**，挂到公网等于把这些都摊开：
     * 源码、/@vite/client、node_modules、以及 /admin 与 /admin/api 的代理。
     * allowedHosts 只挡 DNS rebinding，**不是访问控制** —— 后台真正的门锁是 ADMIN_PASSWORD。
     * 想更稳：把 dist/ 交给后端 8080 提供（SpaForwardController 那条路），
     * 穿透指向 8080 而不是 5173。
     */
    // ⚠️ 故意不写任何真实域名（仓库公开）。要透过隧道/域名访问 dev server，
    //    把域名写进仓库根的 .env（前导点 = 该域名 + 任意子域）：
    //      ALLOWED_HOSTS=.a.com,.b.com
    allowedHosts: ['.localhost', ...TUNNEL_HOSTS],
    /**
     * 容器里改的文件也能触发热更新 / 自动重启（原因见上面 USE_POLLING 的注释）。
     * interval 越小越灵、越费 CPU；300ms 是这个项目上够用又不吵的值。
     * ⚠️ 这一项改了同样要重启一次 dev server 才生效。
     */
    watch: {
      usePolling: USE_POLLING,
      interval: 300,
    },
    proxy: {
      '/api': { target: API_TARGET, changeOrigin: true },
      '/admin/api': { target: API_TARGET, changeOrigin: true },
    },
  },
})
