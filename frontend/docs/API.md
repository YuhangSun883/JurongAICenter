# 前端 → 后端 接口需求清单

> 命名约定：所有 REST 接口挂在 `/api` 前缀下。鉴权用 `Authorization: Bearer <token>`。
> 响应包装（推荐）：`{ code: 0, message: 'ok', data: T }`，但前端也接受裸对象 / 裸数组。

## 通用约定

- 列表接口统一支持 `?page=1&pageSize=20&keyword=xxx`
- 列表响应：`{ items: T[]; total: number; page: number; pageSize: number }`
- 时间戳统一毫秒（number）
- 媒体 url 用 https 绝对地址（OSS / CDN），前端不拼域名
- 鉴权 header：`Authorization: Bearer <token>`（`token` 取自 `localStorage[TOKEN_KEY]`）

## 当前已注册业务域

| 领域 | 状态 | 文件 | 文档小节 |
|---|---|---|---|
| `auth` 用户认证 | ✅ mock | `src/api/auth.ts` | [§1](#1-用户认证-authts) |
| `agent` AI 聊天 | ✅ mock | `src/api/agent.ts` | [§2](#2-agent-聊天-agentts) |
| `video` 视频生成 | ✅ mock | `src/api/video.ts` | [§3](#3-视频生成-videots) |
| `media` 资产库 / 素材 | ✅ mock | `src/api/media.ts` | [§4](#4-媒体资产库--素材-mediat) |
| `creations` 统一创作（视频/图片/Agent） | ✅ mock | `src/api/creations.ts` | [§5](#5-统一创作-creations) |
| `canvas` 画布节点 | ✅ mock | `src/api/canvas.ts` | [§6](#6-画布节点-canvasts) |

> **新增功能必须**：在 `src/api/config.ts` 的 `APIS` 数组登记 + 在本表加一行 + 在下面对应小节写接口表 + 在 `docs/CHANGELOG.md` 写一行。

---

## 1. 用户认证 (`src/api/auth.ts`)

| ✅ | 方法 | 路径 | 请求 | 响应 |
|---|---|---|---|---|
| ☐ | POST | `/api/auth/login` | `{ email?, phone?, code?, password? }` | `{ token, user: UserInfo }` |
| ☐ | POST | `/api/auth/logout` | - | - |
| ☐ | GET  | `/api/auth/me` | - | `UserInfo` |

`UserInfo = { id, nickname, avatar?, email? }`

---

## 2. Agent 聊天 (`src/api/agent.ts`)

### 2.1 会话
| ✅ | 方法 | 路径 | 请求 | 响应 |
|---|---|---|---|---|
| ☐ | GET  | `/api/agent/sessions?page=&pageSize=&keyword=&pinned=` | - | `PageResult<AgentSession>` |
| ☐ | POST | `/api/agent/sessions` | `{ title? }` | `{ session: AgentSession }` |
| ☐ | PATCH| `/api/agent/sessions/:id` | `{ title }` | `AgentSession` |
| ☐ | DELETE| `/api/agent/sessions/:id` | - | - |

`AgentSession = { id, title, pinned?, createdAt, updatedAt, creditsUsed? }`

### 2.2 消息
| ✅ | 方法 | 路径 | 请求 | 响应 |
|---|---|---|---|---|
| ☐ | GET  | `/api/agent/sessions/:id/messages?page=&pageSize=` | - | `PageResult<AgentMessage>` |

`AgentMessage = { id, sessionId, role: 'user'|'assistant'|'system', content, attachments?, toolCalls?, createdAt }`
- `attachments = { id, type, url, name }[]`
- `toolCalls = { key, label, status: 'pending'|'done'|'failed' }[]`

### 2.3 发送
| ✅ | 方法 | 路径 | 请求 | 响应 |
|---|---|---|---|---|
| ☐ | POST | `/api/agent/send` | `AgentSendRequest` | `AgentSendResponse` |
| ☐ | POST | `/api/agent/send/stream` | `AgentSendRequest` | `text/event-stream` |

`AgentSendRequest = { sessionId: string\|null, content, attachmentIds?: string[], tools?: AgentTool[], roleIds?: string[] }`
`AgentSendResponse = { sessionId, userMessageId, assistantMessageId, creditsUsed, creditsEstimated }`
`AgentTool = 'search' | 'web' | 'voice' | 'kb' | 'skill'`

### 2.4 积分
| ✅ | 方法 | 路径 | 响应 |
|---|---|---|---|
| ☐ | GET  | `/api/agent/credits` | `{ total, used, remaining, estimated? }` |
| ☐ | POST | `/api/agent/credits/check` | `CreditsCheckResponse` |

`CreditsCheckRequest = { action: 'agent-send'\|'video-create'\|'image-create', estimated, context? }`
`CreditsCheckResponse = { status: 'ok'\|'insufficient'\|'unknown', remaining, required, code?, message?, upgradeUrl? }`

### 2.5 套餐 / 订单
| ✅ | 方法 | 路径 | 请求 | 响应 |
|---|---|---|---|---|
| ☐ | GET    | `/api/agent/plans` | - | `PlanInfo[]` |
| ☐ | POST   | `/api/agent/plans/orders` | `{ planId, payMethod? }` | `CreatePlanOrderResponse` |
| ☐ | GET    | `/api/agent/plans/orders/:id` | - | `QueryOrderResponse`（轮询用） |
| ☐ | POST   | `/api/agent/plans/orders/:id/cancel` | - | - |
| ☐ | GET    | `/api/agent/contact?scope=enterprise|general` | - | `ContactInfoResponse` |
| ☐ | GET    | `/api/agent/credits/packages` | - | `CreditPackage[]` |
| ☐ | POST   | `/api/agent/credits/orders` | `{ packageId, payMethod? }` | `CreateCreditsOrderResponse` |
| ☐ | POST   | `/api/agent/credits/redeem` | `{ code }` | `RedeemCardResponse` |

### 2.6 商品套图（商详）
| ✅ | 方法 | 路径 | 请求 | 响应 |
|---|---|---|---|---|
| ☐ | GET    | `/api/product-image/models` | - | `ProductImageModel[]` |
| ☐ | GET    | `/api/product-image/settings` | - | `ProductImageSetting[]` |
| ☐ | GET    | `/api/product-image/examples` | - | `ProductImageExample[]` |
| ☐ | POST   | `/api/product-image/tasks` | `CreateProductImageRequest` | `ProductImageTask` |
| ☐ | GET    | `/api/product-image/tasks/:taskId` | - | `ProductImageTask`（轮询用） |

`ProductImageModel = { key, label, badge?, creditsCost }`
`ProductImageSetting = { key: '1K · JPEG' \| '2K · PNG' \| '4K · JPEG', label }`
`ProductImageExample = { id, title, subtitle, description?, imageUrl, order }`
`CreateProductImageRequest = { assetIds, lang, count, brief?, modelKey, settingKey }`
`ProductImageTask = { taskId, status: 'pending'|'running'|'success'|'failed', imageUrls?, creditsCost, createdAt, failReason? }`

> 工作台流程：选商品图（1~5 张）→ 选语言 / 张数 / 模型 / 设置 → 写补充说明 → 点「立即分析」 → 调 `POST /api/product-image/tasks` → 前端轮询 `GET /api/product-image/tasks/:taskId` → 完成后渲染结果图。

`PlanInfo = { id, title, badge?, price, originalPrice?, description, credits, validDays, features[], highlighted?, cta: 'primary'|'ghost'|'contact' }`
`PayMethod = 'alipay' | 'wechat' | 'card'`
`CreatePlanOrderResponse = { orderId, payUrl, amount, qrCodeUrl?, qrCodeContent?, payMethod, expireAt, status }`
`QueryOrderResponse = { orderId, status: 'pending'|'paid'|'cancelled'|'expired'|'refunded', paidAt?, failReason?, amount, planId, receipt?: { creditsAdded, validDays } }`
`ContactInfoResponse = { title, description, channels: ContactChannel[], footerHint? }`
`ContactChannel = { method: 'wechat'|'alipay'|'card'|'phone'|'email', qrCodeUrl?, qrCodeContent?, value?, description? }`
`CreditPackage = { id, price, credits, highlighted? }`
`CreateCreditsOrderResponse = { orderId, payUrl, amount, credits, qrCodeUrl?, qrCodeContent?, payMethod, expireAt, status }`
`RedeemCardResponse = { creditsAdded, validDays, redeemId, remainingCredits }`

> 支付流程：前端弹二维码 → 用户扫码 → 第三方支付回调后端 → 用户前端轮询 `GET /api/agent/plans/orders/:id` → 后端返回 `status='paid'` → 前端显示成功 + 触发 `receipt` 入账。
> 客服流程：企业套餐的「联系客服」调 `GET /api/agent/contact?scope=enterprise` → 后端返回多个渠道（微信/支付宝/邮箱...） → 前端通用渲染。**后续换自己的二维码只需后端改 qrCodeUrl**，前端零改动。
> 购买积分：「购买积分」链接触发 → 拉 `GET /api/agent/credits/packages` → 选完点「立即充值」调 `POST /api/agent/credits/orders` → 复用 `PaymentDialog` 弹二维码。
> 兑换充值卡：用户输入卡密 → 点「确认兑换」调 `POST /api/agent/credits/redeem` → 后端查卡密（未使用/未过期）→ 入账积分 → 前端显示成功态 + 业务侧 `onPaid` 刷新积分。
> **卡密由后端生成**（客户付款 → 后台出码 → 兑换时对应金额入账），前端只负责输入 + 调接口。

---

## 3. 视频生成 (`src/api/video.ts`)

| ✅ | 方法 | 路径 | 请求 | 响应 |
|---|---|---|---|---|
| ☐ | POST | `/api/videos/script` | `GenerateVideoScriptRequest` | `GenerateVideoScriptResponse` |
| ☐ | POST | `/api/videos` | `CreateVideoRequest` | `{ taskId, estimatedCredits }` |
| ☐ | GET  | `/api/videos/:id` | - | `VideoTask` |
| ☐ | GET  | `/api/videos?status=&page=&pageSize=` | - | `{ items: VideoTask[], total }` |
| ☐ | POST | `/api/videos/:id/cancel` | - | - |
| ☐ | POST | `/api/videos/:id/retry` | - | `{ taskId, estimatedCredits }` |

`GenerateVideoScriptRequest = { brief?, model, aspectRatio, duration, audioMode?, referenceIds }`

`GenerateVideoScriptResponse = { script, model?, creditsEstimated? }`

点击视频工作台的「帮我写」时，前端调用 `POST /api/videos/script`，由后端模型根据当前模型、比例、时长、音频模式和素材上下文生成脚本。当前 `NEXT_PUBLIC_USE_MOCK=true` 时由 `src/api/video.mock.ts` 返回 mock 文案；后端接入后只需切换配置，不需要修改页面组件。

`CreateVideoRequest = { script, model, aspectRatio, resolution, duration, audioMode?, referenceIds }`

当前视频工作台只实现前端交互，默认通过 `src/api/video.mock.ts` 模拟任务创建、轮询和进度推进；后端接口暂不实现。后续对接时，`audioMode` 可选：

```ts
type VideoModel =
  | 'Seedance-2.0-VIP'
  | 'Seedance-2.0-Fast-VIP'
  | 'Seedance-2.0-Mini-VIP';

type AspectRatio = '21:9' | '16:9' | '4:3' | '1:1' | '3:4' | '9:16';
type Resolution = '480p' | '720p' | '1080p';
type AudioMode = 'with-audio' | 'mute';
type Duration = 4 | 5 | 6 | /* ... */ 29 | 30;
```

字段说明：

- `model`：视频模型 key，由前端模型下拉选择。
- `aspectRatio`：视频比例，由前端比例面板选择。
- `resolution`：视频分辨率。
- `audioMode`：是否生成音频；未传时后端可按 `with-audio` 处理。
- `duration`：视频时长，单位秒，范围 `4-30`。
- `referenceIds`：前端素材选择器提交的素材 id；当前本地上传仍使用临时 object URL，后续需接入素材上传接口。
`VideoTask = { id, status: 'queued'|'running'|'succeeded'|'failed', progress: 0-100, request, resultUrl?, thumbnailUrl?, error?, estimatedCredits, createdAt, updatedAt }`

---

## 4. 媒体（资产库 / 素材）(`src/api/media.ts`)

> **核心模型**：
> - **资产库（MediaLibrary）**：用户维度的容器，每个用户注册时自动创建 2 个系统默认库
>   - 「我的资产」(`type=system-uploaded`)：存放用户上传的素材
>   - 「AI 生成结果」(`type=system-ai`)：存放 AI 生成的素材
>   - 用户可新建自定义库 (`type=custom`)，可重命名/删除；系统库**不可**重命名/删除
> - **素材（MediaAsset）**：图片/视频/音频，归属到某个库
>   - `source=uploaded` 用户上传 / `source=ai-generated` AI 生成
>   - 删除自定义库时**级联删除**库内素材（同时删除 MinIO 对象）
>
> **业务接入**：
> - 注册时由后端 `AuthServiceImpl` 自动调用 `MediaLibraryService.createDefaultLibraries` 建库
> - AI 生成完成时由 `GenerationServiceImpl` 调用 `MediaService.recordAiGenerated` 写入「AI 生成结果」库

### 4.1 资产库

| ✅ | 方法 | 路径 | 请求 | 响应 |
|---|---|---|---|---|
| ✅ | GET    | `/api/media/libraries` | - | `MediaLibrary[]` |
| ✅ | POST   | `/api/media/libraries` | `CreateLibraryRequest` | `MediaLibrary` |
| ✅ | PATCH  | `/api/media/libraries/{id}` | `RenameLibraryRequest` | `MediaLibrary` |
| ✅ | DELETE | `/api/media/libraries/{id}` | - | - |

```ts
type LibraryType = 'system-uploaded' | 'system-ai' | 'custom';

MediaLibrary = {
  id: number;
  name: string;                       // 库名
  type: LibraryType;                  // 系统库 / 自定义库
  iconKey?: 'folder' | 'star' | 'heart' | 'sparkles' | string;
  description?: string;
  sortOrder?: number;                 // 排序值，小的在前
  assetCount?: number;                // 库内素材数量
  createdAt?: string;                 // ISO timestamp
  updatedAt?: string;
};

CreateLibraryRequest = {
  name: string;                       // 必填，1~100 字符
  iconKey?: string;                   // 可选，默认 'folder'
  description?: string;               // 可选
};

RenameLibraryRequest = {
  name: string;                       // 必填
  iconKey?: string;                   // 可选，不传则保留原值
};
```

> 错误码：
> - `MEDIA_LIBRARY_NAME_DUPLICATE` 库名重复
> - `MEDIA_LIBRARY_IS_SYSTEM_CANNOT_MODIFY` 系统库不可重命名/删除
> - `MEDIA_LIBRARY_NOT_FOUND` 库不存在或不属于当前用户

### 4.2 素材

| ✅ | 方法 | 路径 | 请求 | 响应 |
|---|---|---|---|---|
| ✅ | GET    | `/api/media/assets?libraryId=&type=&source=&keyword=&page=&pageSize=` | - | `PageResult<MediaAsset>` |
| ✅ | GET    | `/api/media/assets/{id}` | - | `MediaAsset` |
| ✅ | POST   | `/api/media/assets` | `multipart/form-data (file, libraryId?)` | `MediaUploadResponse` |
| ✅ | PATCH  | `/api/media/assets/{id}` | `PatchAssetRequest` | `MediaAsset` |
| ✅ | DELETE | `/api/media/assets/{id}` | - | - |
| ✅ | POST   | `/api/media/assets/batch-delete` | `BatchDeleteAssetsRequest` | `{ deleted: number, requested: number }` |

```ts
type MediaType = 'image' | 'video' | 'audio';
type MediaSource = 'uploaded' | 'ai-generated';

MediaAsset = {
  id: number;
  libraryId: number;                  // 所属资产库
  libraryName?: string;               // 冗余字段，列表时填充
  type: MediaType;
  source: MediaSource;
  name: string;                       // 文件名
  mimeType?: string;
  sizeBytes?: number;
  width?: number;
  height?: number;
  durationSec?: number;               // 视频/音频时长
  url: string;                        // MinIO 24h 预签名 URL，前端直接展示
  sourceTool?: 'video'|'image'|'canvas'|'agent'|'upload'|string;
  sourceTaskId?: string;              // 关联的生成任务 ID（AI 素材时）
  createdAt: string;                  // ISO timestamp
  updatedAt?: string;
};

MediaUploadResponse = {
  id: number;
  url: string;                        // 预签名 URL
  name: string;
  type: MediaType;
  size: number;                       // bytes
};

PatchAssetRequest = { name: string };
BatchDeleteAssetsRequest = { ids: number[] };
```

> **上传限制**（后端 `MediaServiceImpl.checkSize`）：
> - 图片 ≤ 20 MB
> - 视频 ≤ 200 MB
> - 音频 ≤ 50 MB
> - 支持 MIME：`image/*` `video/*` `audio/*`，或按扩展名兜底
> - 前端调用时**不要**自己设 `Content-Type`，让浏览器自动生成 `multipart/form-data` 边界
>
> **下载**：返回的 `url` 是 24h 有效的 MinIO 预签名 URL，前端直接 `<img src=url>` 展示即可；不要在客户端持久化该 URL。
>
> 错误码：
> - `MEDIA_FILE_EMPTY` 文件为空
> - `MEDIA_ASSET_TYPE_INVALID` 文件类型不支持
> - `MEDIA_FILE_TOO_LARGE` 超过大小限制
> - `MEDIA_UPLOAD_FAILED` MinIO 上传失败
> - `MEDIA_ASSET_NOT_FOUND` 素材不存在或不属于当前用户

### 4.3 角色库（兼容旧接口）

> 历史「角色库」功能已并入资产库的 `type=custom` 库，下方接口保留向后兼容，**新业务请走 4.1/4.2**。

| ✅ | 方法 | 路径 | 请求 | 响应 |
|---|---|---|---|---|
| ☐ | GET  | `/api/media/roles/categories` | - | `RoleCategory[]` |
| ☐ | GET  | `/api/media/roles?category=&keyword=&page=&pageSize=` | - | `{ items: MediaItem[], total: number }` |

`RoleCategory = { key: string; label: string }`

---

## 5. 统一创作 (`src/api/creations.ts`)

> **首页 ScriptCard 三合一入口**：视频生成 / 图片生成 / Agent 模式共用同一套接口，由 `type` 字段区分。
> Agent 模式额外走 `/api/agent/chat`（多轮对话 + 工具调用），由后端 Agent 服务处理。

### 5.1 创建任务（视频 / 图片）

| ✅ | 方法 | 路径 | 请求 | 响应 |
|---|---|---|---|---|
| ☐ | POST | `/api/creations` | `CreateCreationRequest` | `CreationTask` |
| ☐ | GET  | `/api/creations/:taskId` | - | `CreationTask`（轮询用） |
| ☐ | GET  | `/api/creations?type=&page=&pageSize=` | - | `{ items: CreationTask[], total }` |

```ts
type CreationType = 'video' | 'image' | 'agent';

CreateCreationRequest = {
  type: 'video' | 'image';      // Agent 不走这个，走 5.2
  prompt: string;                 // 提示词 / 脚本
  materialIds?: string[];         // 引用的素材 id（来自素材库）
  modelKey?: string;              // 可选模型 key（默认后端选）
};

CreationTask = {
  taskId: string;
  type: 'video' | 'image';
  status: 'pending' | 'running' | 'success' | 'failed';
  resultUrl?: string;             // 完成后：视频 / 图片 URL
  failReason?: string;            // 失败原因
  createdAt: number;              // 毫秒时间戳
};
```

### 5.2 Agent 对话

| ✅ | 方法 | 路径 | 请求 | 响应 |
|---|---|---|---|---|
| ☐ | POST | `/api/agent/chat` | `AgentChatRequest` | `AgentChatResponse` |
| ☐ | POST | `/api/agent/chat/stream` | `AgentChatRequest` | `text/event-stream`（流式） |

```ts
AgentChatRequest = {
  conversationId?: string;        // 续传；首次为空
  message: string;                // 用户消息
  materialIds?: string[];         // 引用的素材
};

AgentChatResponse = {
  conversationId: string;         // 用于续传
  reply: string;                  // Agent 回复
  actions?: {                     // Agent 调用的工具 / 动作
    name: string;
    status: 'ok' | 'fail';
    result?: unknown;
  }[];
};
```

> **后端职责**：
> - 视频生成 → 调视频生成模型（Sora / 可灵 / Vidu 等）
> - 图片生成 → 调图片生成模型（Midjourney / SDXL / Flux 等）
> - Agent 模式 → 多轮对话 + 工具调用（搜索 / 网络 / 知识库 / 技能等），可能返回 `actions` 让前端展示调用过程

> **前端职责**：
> - 收集 prompt + materialIds + mode
> - 调对应接口 + 展示任务状态 / 任务队列 / 结果

---

## 6. 画布节点 (`src/api/canvas.ts`)

> 当前只实现前端画布交互：点击「文本 / 图片」会在画布中创建对应节点；点击生成箭头时预留 `canvasApi.generateNode` 调用。后端接入后，模型生成、素材上传、节点持久化都由后端实现。

| ✅ | 方法 | 路径 | 请求 | 响应 |
|---|---|---|---|---|
| ☐ | POST | `/api/canvas/nodes` | `CreateCanvasNodeRequest` | `CanvasNode` |
| ☐ | PATCH | `/api/canvas/nodes/:nodeId` | `UpdateCanvasNodeRequest` | `CanvasNode` |
| ☐ | POST | `/api/canvas/nodes/:nodeId/generate` | `GenerateCanvasNodeRequest` | `GenerateCanvasNodeResponse` |

```ts
type CanvasNodeType = 'text' | 'image' | 'video' | 'audio';

CanvasNode = {
  id: string;
  type: CanvasNodeType;
  title: string;
  content?: string;
  assetId?: string;
  resultUrl?: string;
  createdAt: number;
  updatedAt: number;
};

CreateCanvasNodeRequest = {
  type: CanvasNodeType;
  title?: string;
  content?: string;
  assetId?: string;
};

UpdateCanvasNodeRequest = {
  nodeId: string;
  title?: string;
  content?: string;
  assetId?: string;
};

GenerateCanvasNodeRequest = {
  nodeId: string;
  type: CanvasNodeType;
  prompt: string;
  content?: string;
  assetIds?: string[];
  settings?: Record<string, unknown>;
};

GenerateCanvasNodeResponse = {
  taskId: string;
  nodeId: string;
  status: 'pending' | 'running' | 'success' | 'failed';
  text?: string;
  resultUrl?: string;
  creditsEstimated: number;
};
```

---

## 前端切换开关

`.env.local`：
```ini
NEXT_PUBLIC_USE_MOCK=false
NEXT_PUBLIC_API_BASE_URL=https://your-api.com
```

把 `USE_MOCK` 改成 `false`，前端所有调用会走 `*.real.ts`。**业务代码不需要改**。

---

## 接入检查清单

后端接口完成后，对照这份清单逐个打钩，每完成一个就跟前端同步一次。
