# web/ —— 飘雪喵 前端（双端）

与 `server/` 同级。**两个端**，共用主题和组件：

| 端 | 入口 | 给谁 | 鉴权 |
|---|---|---|---|
| `public` | `public.html` | 群友（公开站） | 无 |
| `admin` | `admin.html` | 你（管理后台） | 密码 |

---

## 快速开始

```bash
# 1) 装依赖（store 放在工作区内，避免写到容器外）
pnpm install --store-dir ../.toolchain/pnpm-store

# 2) 起开发服务器（会把 /api 和 /admin/api 代理到 localhost:8080）
pnpm dev

#    公开站 → http://localhost:5173/public.html
#    管理后台 → http://localhost:5173/admin.html
```

开发时后端要单独跑（在 `server/` 里 `mvn spring-boot:run`）。

## 构建

```bash
# 在仓库根目录执行：构建 + 同步到后端能托管的目录
./scripts/build-web.sh
```

产物 → `web/dist/`，并拷贝到 `server/web-dist/` 供 Spring Boot 托管。

---

## 目录

```
src/
├─ theme/            设计令牌与质感（「雾中灵火」）
│  ├─ tokens.css     颜色/字体/间距/阴影 —— 改主题只改这里
│  ├─ base.css       重置 + 排版
│  └─ texture.css    噪点/光晕/四角铜饰（"游戏感"来自这里）
├─ shared/           两端共用
│  ├─ api/           client.ts（publicApi / adminApi 严格分开）+ types.ts
│  ├─ ui/            Panel / Stat / Tag / Button / Bar / Empty
│  └─ utils/         format.ts（数字/时间/百分比）
├─ public/           公开站
└─ admin/            管理后台
```

---

## 主题：「雾中灵火」

颜色**不是拍脑袋定的** —— 从 wiki 官方美术素材提取主色后归纳：

| 令牌 | 值 | 取自 |
|---|---|---|
| `--flame` | `#e8a04c` | 灵火祭坛的火光（唯一高饱和暖色） |
| `--bg-shroud` | `#141922` | Shroud 的雾 |
| `--bg-abyss` | `#0c0e12` | 洞穴深处 |
| `--moss` | `#6b8f5e` | 森林（命中/成功） |
| `--rust` | `#b5533f` | 失败/未命中 |
| `--copper` | `#8a6a3f` | 暗铜装饰描边 |

**只做深色**：游戏本身就是暗色调；群友多在手机上看。

**暖色克制**：满屏都是橙 = 没有重点。`--flame` 只给"最需要看一眼"的元素。

---

## 安全约定（重要）

1. **`publicApi` 与 `adminApi` 严格分开**，路径前缀不同。
   公开站**永远不该**调 `/admin/api/**` —— 即使前端出 bug 也不会误调带权限的接口。

2. **前端路由守卫不是安全边界**，只是体验优化。真正的校验在后端：
   `/admin/api/**` 一律要会话，公开接口一律只读。

3. **公开接口不返回任何隐私字段**。后端用专门的 DTO
   （`PublicDtos`）显式拷贝，绝不复用管理接口的返回值。
