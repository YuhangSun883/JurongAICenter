# JRai 前端技术栈文档

> 本文档由 AI 协助生成，帮助团队成员快速理解项目前端架构。

---

## 一、技术栈概览

| **框架** | Next.js 14.2.18（App Router） |
| **语言** | **TypeScript**（`.tsx` / `.ts`） |
| **UI 库** | **React 18** |
| **样式** | **Tailwind CSS**（自定义 `card` / `chip` / `btn-ghost` 等 utility class）+ `globals.css` |
| **图标** | **lucide-react** |
| **状态管理** | React Context（`MaterialsContext`）+ `useState` / `useRef` |
| **数据请求** | API 层（`src/api/*.ts` + mock 实现 `*.mock.ts`） |
| **部署** | Vercel（推测，看 `next.config` 配置） |

## 文档说明

- [docs/API.md](file:///d:/JRai/docs/API.md) — **后端接口契约**（每个领域都有详细接口表）
- [docs/CHANGELOG.md](file:///d:/JRai/docs/CHANGELOG.md) — **接口变更日志**（每次改动追加一条）
- [docs/NEW_FEATURE.md](file:///d:/JRai/docs/NEW_FEATURE.md) — 新功能开发流程 |

---

## 二、目录结构

```
src/
├── api/                  # API 层（mock + real 双轨）
│   ├── config.ts         # USE_MOCK 开关
│   ├── product-image.*   # 商详套图 API
│   ├── video.*           # 视频 API
│   ├── media.*           # 素材 API
│   ├── agent.* / auth.*  # Agent / 鉴权
│   └── index.ts
│
├── app/                  # Next.js App Router 页面
│   ├── layout.tsx        # 根布局（包 Providers）
│   ├── page.tsx          # 首页
│   ├── globals.css       # 全局样式
│   │
│   ├── tools/            # 工具页面
│   │   ├── product-image/        # 商详套图
│   │   ├── watermark-remover/    # 水印擦除
│   │   ├── image-enhancer/       # 画质增强
│   │   ├── subtitle-remover/     # 字幕擦除
│   │   ├── model-swap/           # 模特换衣
│   │   ├── digital-human/        # 数字分身
│   │   └── viral-video/          # 爆款裂变
│   │
│   ├── ai-image/         # AI 图片
│   ├── ai-video/         # AI 视频
│   ├── canvas/           # 画布
│   ├── agent/            # AI Agent
│   └── assets/           # 素材库
│
├── components/           # 共享组件
│   ├── common/           # 通用组件（跨页面）
│   │   ├── MediaPickerDialog.tsx   # 素材选择弹窗
│   │   ├── StyledSelect.tsx        # 自定义下拉
│   │   ├── SettingsPopover.tsx     # 设置弹层
│   │   ├── BuyCreditsDialog.tsx    # 充值弹窗
│   │   ├── PaymentDialog.tsx       # 支付弹窗
│   │   ├── ContactDialog.tsx       # 联系客服
│   │   └── ...
│   ├── home/             # 首页专用
│   │   ├── Hero.tsx
│   │   ├── ScriptCard.tsx
│   │   ├── Sidebar.tsx
│   │   ├── ToolGrid.tsx
│   │   └── BottomTabs.tsx
│   ├── agent/            # Agent 页面
│   │   ├── ChatComposer.tsx
│   │   └── ChatHistory.tsx
│   └── workbench/        # 工作台（视频场景）
│       ├── Workbench.tsx
│       ├── TopBar.tsx
│       ├── ScriptEditor.tsx
│       ├── TaskQueue.tsx
│       ├── MediaPicker.tsx
│       ├── PreviewPanel.tsx
│       └── VideoSettings.tsx
│
├── contexts/             # React Context 全局状态
│   └── MaterialsContext.tsx   # 全局素材库
│
├── hooks/                # 自定义 Hooks
│   ├── useTaskPolling.ts      # 任务轮询
│   └── useTaskStream.ts       # 任务流式更新
│
├── lib/                  # 工具函数
│   ├── http.ts           # HTTP 请求封装
│   └── utils.ts          # cn() 等
│
├── store/                # 状态管理
│   └── workbench.ts      # Zustand store（视频工作台）
│
└── types/                # TypeScript 类型
    ├── agent.ts
    ├── api.ts
    ├── media.ts
    ├── product-image.ts
    ├── user.ts
    └── video.ts
```

---

## 三、核心架构

### 1. App Router 路由

项目使用 Next.js 14 的 **App Router**（`src/app/`），每个文件夹代表一个路由段：

```
src/app/tools/product-image/page.tsx  →  /tools/product-image
src/app/page.tsx                      →  /
```

### 2. Server vs Client 组件

默认是 Server Component，需要交互的组件顶部加 `'use client'`：

```tsx
'use client';
import { useState } from 'react';

export default function Counter() {
  const [count, setCount] = useState(0);
  return <button onClick={() => setCount(count + 1)}>{count}</button>;
}
```

### 3. 根布局（Providers）

`src/app/layout.tsx` 包全局 Provider：

```tsx
<body className="min-h-screen">
  <MaterialsProvider>{children}</MaterialsProvider>
</body>
```

### 4. Mock / Real API 双轨

`src/api/config.ts` 控制使用 mock 数据还是真实接口：

```ts
export const USE_MOCK = true; // 开发阶段用 mock
```

每个 API 模块都有 `.mock.ts` 和 `.real.ts`：

```ts
// src/api/product-image.ts
export const productImageApi = {
  listModels: () =>
    USE_MOCK ? mock.listModels() : real.listModels(),
  createTask: (req) =>
    USE_MOCK ? mock.createProductImageTask(req) : real.createProductImageTask(req),
};
```

---

## 四、全局状态管理

### 1. MaterialsContext（React Context）

**位置**：`src/contexts/MaterialsContext.tsx`

**作用**：跨页面共享素材库（上传的图片/视频/音频）。

**使用**：

```tsx
import { useMaterials } from '@/contexts/MaterialsContext';

function MyComponent() {
  const { materials, addMaterials, removeMaterial } = useMaterials();
  
  return <div>共 {materials.length} 个素材</div>;
}
```

### 2. workbench store（Zustand）

**位置**：`src/store/workbench.ts`

**作用**：视频工作台的状态（任务队列、当前任务等）。

---

## 五、关键组件

### 1. MediaPickerDialog

**位置**：`src/components/common/MediaPickerDialog.tsx`

**功能**：统一的素材选择弹窗（"我的资产" + "角色库" + 上传）。

**API**：

```tsx
<MediaPickerDialog
  open={open}
  onClose={() => setOpen(false)}
  uploadedFiles={materials}                  // 已上传的素材
  onUploadFiles={(files) => PickedMedia[]}   // 上传回调，返回添加的素材
  onRemoveUploaded={(id) => void}            // 删除回调
  onConfirm={(picked) => void}               // 确认选择回调
  max={5}                                    // 最多选几个
/>
```

### 2. StyledSelect

**位置**：`src/components/common/StyledSelect.tsx`

**功能**：自定义下拉选择器。

### 3. SettingsPopover

**位置**：`src/components/common/SettingsPopover.tsx`

**功能**：分辨率/格式等设置弹层。

---

## 六、API 约定

### 1. 任务状态

```ts
type ProductImageTask = {
  taskId: string;
  status: 'editing' | 'running' | 'success' | 'failed';
  // ...
};
```

### 2. 任务创建流程

```
用户操作（上传/选择/输入）  →  触发任务创建
                    ↓
              'editing' 状态
                    ↓
              点"立即分析"
                    ↓
              'running' 状态
                    ↓
              后端返回结果
                    ↓
              'success' / 'failed'
```

### 3. 任务队列

每个工具页面右侧显示**任务队列**：
- **editing**（编辑中）：待生成的项目
- **running**（生成中）：已提交等待结果
- **success**（已完成）：生成成功
- **failed**（失败）：生成失败

---

## 七、样式规范

### 1. 全局 utility class（`globals.css`）

```css
.card { /* 卡片基础样式 */ }
.chip { /* 标签 */ }
.btn-ghost { /* 透明按钮 */ }
```

### 2. 颜色变量

```css
--bg-base: 背景
--fg:     前景文字
--fg-muted: 次要文字
--bg-soft: 浅背景
--brand:  主题色（蓝色）
```

### 3. 常用组件类

```tsx
<div className="card p-4">卡片</div>
<button className="rounded-xl bg-brand px-4 py-2 text-white">按钮</button>
<span className="chip">标签</span>
```

---

## 八、常见开发任务

### 1. 新增一个工具页面

```bash
# 1. 在 src/app/tools/ 下新建文件夹
mkdir src/app/tools/my-tool

# 2. 创建 page.tsx
touch src/app/tools/my-tool/page.tsx
```

```tsx
// src/app/tools/my-tool/page.tsx
'use client';

export default function MyToolPage() {
  return (
    <main className="mx-auto w-full max-w-[1200px] p-6">
      <h1>我的工具</h1>
    </main>
  );
}
```

### 2. 使用全局素材库

```tsx
import { useMaterials } from '@/contexts/MaterialsContext';

export default function MyToolPage() {
  const { materials, addMaterials } = useMaterials();
  // ...
}
```

### 3. 添加任务到队列

参考 `src/app/tools/product-image/page.tsx` 的实现：

```tsx
const [tasks, setTasks] = useState<ProductImageTask[]>([]);

function createEditingTask() {
  const t: ProductImageTask = {
    taskId: `task_${Date.now()}_${Math.random().toString(36).slice(2, 8)}`,
    status: 'editing',
    creditsCost: 200,
    createdAt: Date.now(),
  };
  setTasks((prev) => [t, ...prev]);
}
```

### 4. 启动 dev server

```bash
pnpm dev
# 或
npm run dev
```

---

## 九、注意事项

### 1. blob URL 不能跨会话

`URL.createObjectURL(file)` 生成的 URL 只能用于**当前页面生命周期**，刷新或关闭浏览器就失效。

**不要**用 localStorage 持久化 blob URL（之前尝试过，已废弃）。

### 2. React 18 双倍渲染

开发环境 React Strict Mode 会双倍触发 `useEffect` / `setInterval` / `setTimeout`。组件需要**幂等**：

```tsx
// ✅ 幂等
const fp = files.map(f => f.name).join('|');
if (lastHandledRef.current === fp) return;
lastHandledRef.current = fp;
```

### 3. 任务队列只能有 1 个 editing

"编辑中"任务同一时间只能有 1 个，新的会**覆盖**旧的（不是累加）：

```tsx
setTasks((prev) => prev.filter((t) => t.status !== 'editing')); // 先清空
const t = { status: 'editing', ... };
setTasks((prev) => [t, ...prev]); // 再加新的
```

### 4. 弹窗里选图必须点"确认选择"

用户上传图 → 只进 `materials`（全局素材库）→ **不**进 `assets`（当前任务已选）。

只有点"确认选择"才进 `assets`，避免"还没确认就显示"。

---

## 十、相关链接

- [Next.js 文档](https://nextjs.org/docs)
- [Tailwind CSS 文档](https://tailwindcss.com/docs)
- [lucide-react 图标库](https://lucide.dev/)
- [Zustand 文档](https://github.com/pmndrs/zustand)