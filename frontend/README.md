# 聚融 · 电商 AIGC 工作台

为电商场景设计的 AIGC 工作台前端骨架。

- **框架**：Next.js 14 (App Router) + React 18 + TypeScript
- **样式**：Tailwind CSS 3
- **状态**：Zustand
- **图标**：lucide-react
- **通信**：fetch + EventSource（轮询默认，预留 SSE）

## 文档

- [docs/API.md](file:///d:/JRai/docs/API.md) — 前端 → 后端 接口契约清单
- [docs/CHANGELOG.md](file:///d:/JRai/docs/CHANGELOG.md) — 接口变更日志
- [docs/NEW_FEATURE.md](file:///d:/JRai/docs/NEW_FEATURE.md) — 新增功能的标准流程

## 启动

```bash
pnpm install        # 或 npm install / yarn
cp .env.example .env.local
pnpm dev            # 打开 http://localhost:3000
```

> 默认 `NEXT_PUBLIC_USE_MOCK=true`，前端内置 mock 推进任务，**无需后端即可体验完整流程**。

## 目录结构

```
src/
├── app/                # Next.js App Router
│   ├── layout.tsx
│   ├── page.tsx
│   └── globals.css
├── api/                # 后端对接层（mock / real 切换）
│   ├── config.ts       # USE_MOCK 开关
│   ├── video.ts        # 业务侧统一入口
│   ├── video.real.ts   # 真后端实现（基于 fetch）
│   ├── video.mock.ts   # 前端内置 mock
│   └── media.ts        # 真实 HTTP 客户端
├── components/         # UI 组件
│   └── workbench/
│       ├── Workbench.tsx
│       ├── TopBar.tsx
│       ├── ScriptEditor.tsx     # @ 引用提示的脚本输入
│       ├── VideoSettings.tsx    # 模型 / 比例 / 分辨率 / 时长
│       ├── MediaPicker.tsx      # 视频/音频/图片 上传
│       ├── PreviewPanel.tsx     # 视频预览 + 进度
│       └── TaskQueue.tsx        # 任务队列
├── hooks/
│   ├── useTaskPolling.ts        # 默认 2s 轮询
│   └── useTaskStream.ts         # 预留 SSE
├── lib/
│   ├── http.ts                  # 统一 fetch 封装
│   └── utils.ts
├── store/
│   └── workbench.ts             # Zustand 全局状态
└── types/
    └── video.ts                 # 后端接口契约
```

## 切换到真后端

后端同学只需做两件事：

1. 在 `.env.local` 改：

   ```ini
   NEXT_PUBLIC_USE_MOCK=false
   NEXT_PUBLIC_API_BASE_URL=https://your-api.com
   ```

2. 按 `src/types/video.ts` 的字段实现下列 REST 接口：

   | 方法 | 路径 | 说明 |
   |---|---|---|
   | POST | `/api/videos` | 创建生成任务，返回 `{ taskId, estimatedCredits }` |
   | GET  | `/api/videos/:id` | 查询任务详情（轮询用） |
   | GET  | `/api/videos?status=&page=&pageSize=` | 列表 |
   | POST | `/api/videos/:id/cancel` | 取消 |
   | POST | `/api/videos/:id/retry` | 重新生成 |
   | GET  | `/api/videos/stream` | （可选）SSE 事件流，事件名 `task` |

前端已经自动从 `localStorage.token` 取 Bearer Token，后端接 JWT/会话态直接读 Header 即可。

## 把轮询换成 SSE

在 `Workbench.tsx` 里：

```tsx
// import { useTaskPolling } from '@/hooks/useTaskPolling';
import { useTaskStream } from '@/hooks/useTaskStream';
useTaskStream(true);   // 打开 SSE
```

后端按事件名 `task` push `VideoTask` JSON 即可。

## 后续可加

- 接入 shadcn/ui（按钮/对话框/Toast）
- 接入 Tiptap 替换 ScriptEditor（更专业富文本 + mention）
- 真实素材上传到对象存储（替换 MediaPicker 里的 `URL.createObjectURL`）
- 鉴权 / 积分扣减回调
