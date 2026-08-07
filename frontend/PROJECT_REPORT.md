# JRai 前端项目阶段性报告

报告日期：2026 年 8 月 5 日

## 一、项目概述

JRai 是一个面向电商内容生产的 AIGC 前端工作台，当前阶段以完成前端页面、交互流程和后端接口预留为主，不包含后端服务实现。

项目采用 Next.js App Router 架构，页面按照独立功能拆分，每个功能拥有独立路由和工作台组件。后续后端完成后，可以在不大幅调整页面结构的情况下接入登录、素材、任务和生成类接口。

## 二、当前技术栈

- Next.js 14
- React 18
- TypeScript
- Tailwind CSS
- Zustand
- Framer Motion
- lucide-react
- nanoid

项目启动命令：

```bash
npm.cmd run dev
```

类型检查命令：

```bash
npm.cmd run typecheck
```

## 三、已完成内容

### 1. 全局基础框架

- 已完成全局左侧导航栏。
- 已完成首页入口和工具卡片跳转。
- 已完成统一的页面背景、边框、按钮、卡片和弹窗样式。
- 已完成素材上下文和全局素材选择弹窗。
- 上传视频、图片等素材时，可以从统一的素材弹窗进入。

### 2. 首页和功能入口

首页已提供以下功能入口：

- AI 视频
- AI 图片
- Agent
- 画布
- 资产
- 商品详情套图
- 水印擦除
- 字幕擦除
- 画质增强
- 爆款裂变
- 数字人
- 模特换装

其中部分功能已经具备独立工作台，部分功能仍为占位页面或基础原型。

### 3. 登录功能前端预留

- 已完成账号密码形式的登录弹窗界面。
- 已预留登录、Token 保存、用户信息保存和错误提示逻辑。
- 已预留注册入口。
- 登录页面当前按照之前的要求暂不单独展示，`/login` 会返回首页。
- 后端登录和注册接口完成后，可以直接接入现有 `authApi`。

### 4. 视频工具工作台

#### 水印擦除

路由：

```text
/tools/watermark-remover
```

已完成：

- 视频上传入口。
- 水印区域卡片。
- 预览区域。
- 操作指引。
- 任务队列。
- 开始擦除按钮。
- 后端任务接口预留。

#### 字幕擦除

路由：

```text
/tools/subtitle-remover
```

已完成：

- 首页「字幕擦除」卡片跳转。
- 独立字幕擦除工作台。
- 视频上传。
- 字幕区域展示。
- 操作指引。
- 预览区域。
- 任务队列。
- 开始擦除按钮。
- 与全局素材上传弹窗联动。
- 已调整响应式断点，保证桌面端左侧操作区、中间预览区和右侧任务队列横向排列。

主要文件：

- `src/app/tools/subtitle-remover/page.tsx`
- `src/components/workbench/SubtitleRemoverWorkbench.tsx`

#### 画质增强

路由：

```text
/tools/image-enhancer
```

已完成：

- 首页「画质增强」卡片跳转。
- 独立画质增强工作台。
- 视频上传。
- 版本选择：标准版、专业版。
- 视频设置选择。
- 操作指引。
- 预览区域。
- 任务队列。
- 开始增强按钮。
- 与全局素材上传弹窗联动。
- 已修复三栏布局在部分浏览器视口下被纵向堆叠的问题。

主要文件：

- `src/app/tools/image-enhancer/page.tsx`
- `src/components/workbench/ImageEnhancerWorkbench.tsx`

### 5. 其他已有功能

- AI 视频工作台已具备脚本输入、模型选择、比例选择、分辨率选择、时长设置、参考素材和任务队列。
- 商品详情套图页面已具备较完整的前端创作流程。
- Agent 页面已具备聊天记录、输入区、工具调用展示和积分相关交互。
- 画布页面已具备画布节点和工作区基础结构。
- 资产页面已具备素材展示和选择基础能力。

## 四、接口架构

项目采用 `mock / real` 双实现模式：

```text
src/api/<domain>.ts
├── <domain>.mock.ts
└── <domain>.real.ts
```

统一入口位于：

```text
src/api/index.ts
```

当前已登记的业务域：

- `auth`
- `agent`
- `video`
- `media`
- `creations`
- `canvas`
- `watermark`

配置文件：

```text
src/api/config.ts
.env.example
```

当前默认配置：

```env
NEXT_PUBLIC_USE_MOCK=true
NEXT_PUBLIC_API_BASE_URL=http://localhost:4000
```

后端完成后，将 `NEXT_PUBLIC_USE_MOCK` 改为 `false`，并配置真实后端地址即可切换到真实接口实现。

## 五、当前验证结果

已完成验证：

- `npm.cmd run typecheck` 通过。
- `http://localhost:3000/tools/subtitle-remover` 返回 HTTP 200。
- `http://localhost:3000/tools/image-enhancer` 返回 HTTP 200。
- 字幕擦除和画质增强页面可以正常渲染。
- 两个工作台的桌面端三栏布局已经验证。
- 页面点击上传入口可以进入全局素材选择弹窗。

当前本地开发地址：

```text
http://localhost:3000
```

## 六、当前未完成内容

以下内容仍需要后端接口或进一步联调：

- 登录、注册、忘记密码真实接口。
- 用户登录态校验和权限控制。
- 视频上传到后端或对象存储。
- 水印擦除真实任务提交。
- 字幕擦除真实任务提交。
- 画质增强真实任务提交。
- 任务进度查询、失败重试和结果下载。
- 任务队列跨页面持久化。
- 积分扣除和余额校验。
- 真实素材库数据。
- 支付、兑换和套餐相关接口。
- 数字人、模特换装、爆款裂变等功能的正式工作台。

目前字幕擦除和画质增强页面中的任务队列主要用于展示前端交互流程，尚未连接真实处理服务。

## 七、GitHub 上传状态

之前已将前端基础版本单独放入仓库的 `frontend/` 目录，避免与后端代码混淆。

已完成信息：

- 仓库：`YuhangSun883/JurongAICenter`
- 前端目录：`frontend/`
- 分支：`codex/add-frontend-app`
- 基础提交：`94bb97d add frontend app`
- 草稿 PR：`#1`

需要注意：

本报告生成时，最近新增的字幕擦除、画质增强页面以及响应式布局修复是在 `D:\JRai` 本地工作区完成的，尚未同步到之前的 GitHub 草稿 PR。后续应将这些最新改动复制或提交到仓库的 `frontend/` 目录后，再更新 PR。

## 八、后续开发建议

### 第一阶段：确定后端接口契约

优先确定以下接口：

- `POST /api/auth/login`
- `POST /api/auth/register`
- `POST /api/media/upload`
- `POST /api/tools/watermark-remover/tasks`
- `POST /api/tools/subtitle-remover/tasks`
- `POST /api/tools/image-enhancer/tasks`
- `GET /api/tasks`
- `GET /api/tasks/:id`

### 第二阶段：接通任务型功能

- 为字幕擦除和画质增强新增独立的 `types` 文件。
- 新增对应的 `mock.ts` 和 `real.ts`。
- 将页面中的本地任务状态替换为 API 返回结果。
- 接入轮询或 SSE 任务进度。
- 接入成功、失败、重试和下载状态。

### 第三阶段：接入登录和权限

- 完成登录和注册页面。
- 增加统一登录态 Provider。
- 对需要登录的工具增加登录拦截。
- 统一处理 Token 失效和刷新。

### 第四阶段：同步 GitHub

- 将 `D:\JRai` 的最新前端改动同步到 `D:\Project\JurongAICenter\frontend`。
- 只提交 `frontend/` 目录。
- 不修改仓库中的后端目录。
- 更新草稿 PR 的变更说明和验证结果。

## 九、阶段结论

当前项目已经完成前端基础框架、导航体系、素材上传弹窗以及多个核心工具页面的视觉和交互原型。字幕擦除和画质增强已经按照参考图完成独立工作台，并解决了桌面端三栏布局问题。

项目目前适合进入“后端接口确定和前后端联调”阶段。后续重点不是继续堆叠页面，而是优先统一任务接口、登录态和素材上传协议，再将现有前端 mock 流程替换为真实数据。
