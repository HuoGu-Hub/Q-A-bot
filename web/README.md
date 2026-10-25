# web

前端双端构建，与 `server/` 同级。

| 端 | 入口 | 使用者 | 鉴权 |
|---|---|---|---|
| `public` | `public.html` | 群友与访客 | 无 |
| `admin` | `admin.html` | 站长 | 会话 |

## 开发

```bash
pnpm install --store-dir ../.toolchain/pnpm-store
pnpm dev
```

公开站为 `http://localhost:5173/public.html`，管理后台为 `http://localhost:5173/admin.html`。开发服务器把 `/api` 与 `/admin/api` 代理到 8080，后端需要单独启动。

## 构建

在仓库根目录执行：

```bash
./scripts/build-web.sh
```

产物先落在 `web/dist/`，再同步到 `server/web-dist/` 由 Spring Boot 托管。构建在 `.toolchain/web-ci/` 的隔离副本中进行，容器与宿主机不共用 `node_modules`。

## 目录

```text
src/
├─ theme/       设计令牌与基础样式
├─ shared/      两端共用
│  ├─ api/      publicApi 与 adminApi 严格分开
│  ├─ ui/       组件库
│  └─ utils/    格式化工具
├─ public/      公开站页面与路由
└─ admin/       管理后台页面与路由
public/
├─ fonts/       自托管标题字体分片
└─ favicon.svg
```

## 组件与主题

- 全站使用 `shared/ui` 中的组件，不直接写原生控件样式。
- 颜色、字号、间距、圆角、阴影只在 `theme/tokens.css` 中定义。
- 图标统一使用 `Icon.vue`，不使用 emoji。
- 页面只使用一套深色主题，暖色仅用于单一重点。

标题字体为思源宋体，按 `unicode-range` 分片自托管。字体文件不进入版本控制，克隆后执行：

```bash
pwsh -File web/scripts/fetch-fonts.ps1
```

## 安全约定

1. `publicApi` 与 `adminApi` 使用不同前缀，公开站不调用 `/admin/api/**`。
2. 前端路由守卫只影响体验，真正的校验在后端。
3. 公开接口使用独立 DTO，不返回用户、群、检索分数等字段。

## 验证

```bash
export PW_ROOT=file:///path/to/node_modules/
node tests/ui/audit.mjs --base http://127.0.0.1:5173
node tests/ui/verify-theme.mjs
```

`audit.mjs` 检查横向溢出、命中区尺寸、控制台错误与主题；`verify-theme.mjs` 检查主题持久化与首帧。截图与视觉产物不进入版本控制。
