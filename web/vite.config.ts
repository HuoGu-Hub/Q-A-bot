import { defineConfig, loadEnv, type Plugin } from 'vite'
import vue from '@vitejs/plugin-vue'
import { fileURLToPath, URL } from 'node:url'
import net from 'node:net'

const here = (p: string) => fileURLToPath(new URL(p, import.meta.url))


const API_TARGET = process.env.API_TARGET ?? 'http://127.0.0.1:8080'

const ROOT_ENV = loadEnv('development', fileURLToPath(new URL('..', import.meta.url)), 'ALLOWED_HOSTS')

const TUNNEL_HOSTS = (process.env.ALLOWED_HOSTS ?? ROOT_ENV.ALLOWED_HOSTS ?? '')
  .split(',')
  .map((s) => s.trim())
  .filter(Boolean)

const USE_POLLING = process.env.WATCH_POLLING !== '0'

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

        if (/\.[a-zA-Z0-9]+$/.test(path) || isBackendCall(path) ||
            path.startsWith('/@') || path.startsWith('/node_modules') ||
            path.startsWith('/src')) {
          next()
          return
        }

        req.url = path.startsWith('/admin') ? '/admin.html' : '/public.html'
        next()
      })
    },
  }
}
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
    allowedHosts: ['.localhost', ...TUNNEL_HOSTS],
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
