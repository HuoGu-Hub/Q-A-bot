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
├─ theme/            设计令牌与质感（「雾中灵火」v2）
│  ├─ fonts.css      自托管标题字体（生成文件，见 scripts/fetch-fonts.ps1）
│  ├─ tokens.css     颜色/字体/间距/阴影 —— 改主题只改这里
│  ├─ base.css       重置 + 排版 + 焦点/触控/窄屏基线
│  └─ texture.css    石板与灯：表面阶梯、刻/凸、噪点、首屏石板
├─ shared/           两端共用
│  ├─ api/           client.ts（publicApi / adminApi 严格分开）+ types.ts
│  ├─ ui/            Icon / Panel / Stat / DataTable / Field / Button …
│  └─ utils/         format.ts（数字/时间/百分比）
├─ public/           公开站
└─ admin/            管理后台

public/
├─ fonts/            101 个思源宋体分片（自托管，见下）
└─ favicon.svg       灵火标记
```

---

## 主题：「雾中灵火」v2

配色方向没变（暗底 + 灵火橙 + 苔藓绿），改的是**观感**：把它从"一堆描了边的深色
小方块"整理成一套有物理感的表面体系。三条规矩：

**① 层级靠光，不靠线。** 面板默认**不描边框**，靠两组内阴影表达凸凹：
`--bevel-raised`（亮在上、暗在下 → 凸出来）、`--bevel-inset`（暗在上、亮在下 →
刻进去）。边框只留给"必须一眼看出能操作"的控件。

**② 一套圆角，三种含义。** 3px 刻痕 / 6px 控件 / 10px 石板 / 16px 画面 / 全圆药丸。
上一版 2/3/6/10 混用，搜索框 6px 而紧挨着的按钮 3px —— 并排圆角不一致是最容易被
眼睛抓到、又最难说清"哪里别扭"的那种低级。

**③ 字号阶梯要真的拉开。** 正文 15px、区块标题 17px、页面标题 29px、首屏 56px。
上一版正文 14px / 标题 16px，差 2px 不算阶梯。

| 令牌 | 值 | 取自 |
|---|---|---|
| `--ember` | `#f2ab55` | 灵火祭坛的火光（全站唯一高饱和暖色） |
| `--stone-200` | `#0b0e13` | 页面底（Shroud 的雾） |
| `--stone-300` | `#12161d` | 石板 / 面板 |
| `--stone-void` | `#06070a` | 光之外的虚空（输入槽、凹槽） |
| `--vital` | `#7ea36a` | 森林（命中 / 成功） |
| `--blight` | `#cf6349` | 失败 / 未命中 |
| `--mist` | `#8b9aab` | 中性蓝灰（次要信息、无状态） |

**只做深色**：游戏本身就是暗色调；群友多在手机上看。

**暖色克制**：一屏之内只有一处被点亮（`.lit`）。满屏都是橙 = 没有重点。

**图标**：全站统一用 `shared/ui/Icon.vue`（24 格 / 1.6px 描边 / `currentColor`）。
**不用 emoji 当图标** —— 每个平台的 emoji 长得都不一样，同一套设计在不同设备上崩掉
就是"廉价感"的定义；而且 emoji 自带高饱和配色，会把"只有一个暖色重点"直接抹掉。

### 标题字体为什么是自托管的

标题用思源宋体（Noto Serif SC）。上一版只写了 `font-family` 却没有 `@font-face`：
开发机装了这套字所以看着挺好，而群友的手机上没有 —— Android 无衬线可退（标题直接
变正文）、Windows 退到 SimSun（大号宋体像 Word 文档）。中文标题的观感八成死在这一步。

自托管 + `unicode-range` 分片之后，浏览器只请求页面真正用到的字；
实测一页通常只命中 5~15 个分片（几百 KB），而不是整包几 MB。

重新生成（换字重、升级字体时）：

```bash
pwsh -File web/scripts/fetch-fonts.ps1
```

---

## 验证前端时的两个坑（都踩过）

**① `prefers-reduced-motion` 会走另一条分支，而且这条分支权重不低。**
Windows 上它跟着「设置 → 辅助功能 → 视觉效果 → 动画效果」走，**关掉的人不少**
（这台开发机就是关着的，`SPI_GETCLIENTAREAANIMATION` 返回 0）。
写动效时如果只在“动画开着”的假设下验证，改到的东西可能根本不是对方看到的那一版。

**② Playwright 的 `reducedMotion` 默认值是 `no-preference`，不是“跟随系统”。**
也就是说，不显式传这个参数，自动化验证会**替你屏蔽掉 reduce 分支**——
于是“改了半天没变化”。要验证真实观感必须两边都显式指定：

    newContext({ reducedMotion: 'reduce' })        // 关掉系统动画的人看到的那版
    newContext({ reducedMotion: 'no-preference' }) // 默认那版

想在开着“动画效果”的设置下看动画，也可以不改系统设置，直接在 DevTools 里临时覆写：
`Ctrl+Shift+P` → **Emulate CSS prefers-reduced-motion: no-preference**。

**③ 顺带一条**：`requestIdleCallback` 是空闲预热的落点。轮播第二张图首次绘制
实测要 220ms（点击 → 画面开始变化），提前 `img.decode()` 之后降到 10ms。
首屏不该为它让路，所以放在空闲时间做。

---

## 安全约定（重要）

1. **`publicApi` 与 `adminApi` 严格分开**，路径前缀不同。
   公开站**永远不该**调 `/admin/api/**` —— 即使前端出 bug 也不会误调带权限的接口。

2. **前端路由守卫不是安全边界**，只是体验优化。真正的校验在后端：
   `/admin/api/**` 一律要会话，公开接口一律只读。

3. **公开接口不返回任何隐私字段**。后端用专门的 DTO
   （`PublicDtos`）显式拷贝，绝不复用管理接口的返回值。