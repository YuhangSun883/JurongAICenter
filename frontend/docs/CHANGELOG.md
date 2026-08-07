# 接口变更日志

> 每次新增 / 修改 / 弃用接口，**追加一条**到这里。
> 时间格式：`YYYY-MM-DD`，领域用 `auth / agent / video / media` 标注。

## 格式

```
## YYYY-MM-DD 领域 · 标题
- 新增：METHOD /path  → 简介
- 修改：METHOD /path  → 改了什么
- 弃用：METHOD /path  → 替代品
```

---

## 2026-08-07 media · 上传类型校验加强

> **目标**：原 `inferType` 只用前缀和扩展名正则匹配，过于宽松——任何带 `.png` 后缀的 `application/octet-stream` 都能通过，攻击者可绕过类型限制上传任意文件。现改为「MIME 白名单 + 扩展名白名单 + 类别一致性 + 黑名单」多重校验。

### 后端

- 修改 `service/impl/MediaServiceImpl.java`
  - 新增白名单常量 `ALLOWED_IMAGE_MIME/VIDEO/AUDIO`、`ALLOWED_IMAGE_EXT/VIDEO/AUDIO`，仅接受明确的具体 MIME 与扩展名
  - 新增黑名单常量 `BLACKLIST_EXT`、`BLACKLIST_MIME`，覆盖可执行/脚本/文档/压缩包/配置/证书/HTML/SVG 等危险类型
  - 重写 `inferType(mime, filename)`：
    1. 先过黑名单（黑命中直接拒绝）
    2. MIME 与扩展名都必须在白名单，且**指向同一类别**（image/video/audio）才接受
    3. 只命中其一可接受；都未命中拒绝
  - 新增辅助 `categorizeByMime` / `categorizeByExt`
  - 错误信息 `MEDIA_ASSET_TYPE_INVALID` 改为包含 mime、filename、允许的类型列表

### 前端

- 修改 `api/media.real.ts`：上传失败时尝试从响应体提取 `message` 字段抛出
- 修改 `app/assets/AssetsView.tsx` 的 `handleUpload`：逐文件 try/catch，错误汇总后 alert 提示

### 文档

- 修改 `API.md`：在 `POST /api/media/assets` 接口行下补充白名单/黑名单说明

---

## 2026-08-07 media · 新增资产库（Media Library）+ 素材资产模块

> **目标**：将原「素材库 / 角色库」重构为「资产库 + 素材」模型，注册时自动建 2 个系统库（"我的资产"/"AI 生成结果"），用户可建自定义库。

### 后端

- 新增 V8 迁移 `V8__media_assets.sql`：建表 `media_libraries`（用户资产库）和 `media_assets`（素材）
  - `media_libraries` 唯一约束 `(user_id, name)`，索引 `idx_user_type` `idx_user_created`
  - `media_assets` 索引 `idx_user_created` `idx_user_type` `idx_user_source` `idx_user_library`
  - 两表均使用 MyBatis Plus `@TableLogic` 软删
- 新增实体 `entity/MediaLibrary.java`、`entity/MediaAsset.java`
- 新增 Repository `repository/MediaLibraryRepository.java`、`repository/MediaAssetRepository.java`（均继承 `BaseMapper<T>` + `@Mapper`）
- 新增 Service 接口与实现：
  - `service/MediaLibraryService(Impl).java`：listLibraries / createLibrary / renameLibrary / deleteLibrary / createDefaultLibraries / getAiLibrary / getUploadLibrary / getOrCreateDefaultCustom
  - `service/MediaService(Impl).java`：listAssets / getAsset / uploadAsset / deleteAsset / batchDeleteAssets / renameAsset / recordAiGenerated / deleteAssetsByLibrary
- 新增 Controller：
  - `controller/MediaLibraryController.java` → `/api/media/libraries`
  - `controller/MediaController.java` → `/api/media/assets`
- 新增 DTO：`MediaListQuery` `MediaAssetResponse` `MediaUploadResponse` `PatchAssetRequest` `BatchDeleteAssetsRequest` `CreateLibraryRequest` `RenameLibraryRequest` `MediaLibraryResponse`
- 新增错误码（7xxx 段）：`MEDIA_LIBRARY_NOT_FOUND` / `MEDIA_LIBRARY_NAME_DUPLICATE` / `MEDIA_LIBRARY_IS_SYSTEM_CANNOT_MODIFY` / `MEDIA_ASSET_NOT_FOUND` / `MEDIA_ASSET_TYPE_INVALID` / `MEDIA_FILE_EMPTY` / `MEDIA_FILE_TOO_LARGE` / `MEDIA_UPLOAD_FAILED`
- `AuthServiceImpl` 注册逻辑：注册成功后调用 `MediaLibraryService.createDefaultLibraries(userId)` 自动建 2 个系统库
- `GenerationServiceImpl` AI 生成完成：调用 `MediaService.recordAiGenerated(...)` 把产物写入"AI 生成结果"库
- 文件上传限制：图片 ≤ 20MB / 视频 ≤ 200MB / 音频 ≤ 50MB（`application.yml` 已放宽 `spring.servlet.multipart` 限制）

### 接口契约

- 新增 `GET  /api/media/libraries` → 列出我的所有库（2 个系统库 + custom 库，按 sortOrder 升序）
- 新增 `POST /api/media/libraries` → 新建 custom 库（`CreateLibraryRequest`）
- 新增 `PATCH /api/media/libraries/{id}` → 重命名（系统库返回 `MEDIA_LIBRARY_IS_SYSTEM_CANNOT_MODIFY`）
- 新增 `DELETE /api/media/libraries/{id}` → 删除 custom 库（级联删除库内素材 + MinIO 对象）
- 新增 `GET  /api/media/assets?libraryId=&type=&source=&keyword=&page=&pageSize=` → 分页列表
- 新增 `GET  /api/media/assets/{id}` → 素材详情
- 新增 `POST /api/media/assets` → `multipart/form-data` 上传（`file`, `libraryId?`），未传 libraryId 默认进"我的资产"
- 新增 `PATCH /api/media/assets/{id}` → 改名
- 新增 `DELETE /api/media/assets/{id}` → 删除（连 MinIO）
- 新增 `POST /api/media/assets/batch-delete` → 批量删除

### 前端

- 新增类型 `types/media.ts`：`MediaLibrary` `MediaAsset` `MediaListQuery` `MediaUploadResponse` 等
- 新增 `api/media.real.ts` / `api/media.mock.ts` / `api/media.ts`（统一出口，按 `USE_MOCK` 分发）
- `mediaApi` 暴露方法：`listLibraries / createLibrary / renameLibrary / deleteLibrary / listAssets / getAsset / uploadAsset / renameAsset / deleteAsset / batchDeleteAssets` + 旧 `listRoleCategories / listRoles` 兼容
- mock 默认数据：2 个系统库（`我的资产` / `AI 生成结果`）+ 5 条示例素材
- 上传走原生 `fetch` + `FormData`（避免 `@/lib/http` 序列化 JSON 时丢 boundary）
- `mediaApi` 已在 `src/api/index.ts` 导出（之前已注册到 `config.ts` 的 `APIS` 数组）

### 文档

- 重写 `docs/API.md` 第 4 节，拆为 4.1 资产库 / 4.2 素材 / 4.3 角色库兼容三段
- 本条变更日志

---

## 2026-08-03 canvas · 新增画布节点生成接口占位

- 新增 `POST /api/canvas/nodes` → 创建文本 / 图片 / 视频 / 音频节点
- 新增 `PATCH /api/canvas/nodes/:nodeId` → 更新节点内容或引用素材
- 新增 `POST /api/canvas/nodes/:nodeId/generate` → 后端模型按节点类型生成文本或图片
- 新增 `src/api/canvas.{ts,mock.ts,real.ts}`，前端当前走 mock，后端接入后切换配置即可

## 2026-08-03 video · 前端视频设置面板

- 前端新增视频比例、分辨率、音频模式、时长的自定义下拉面板
- `CreateVideoRequest` 预留 `audioMode` 字段，当前仍使用 mock，不实现后端接口
- 视频模型、比例和字段说明同步补充到 `docs/API.md`
- 「帮我写」接入 `videoApi.generateScript`，预留 `POST /api/videos/script`

## 2026-08-01 初始化

- 新建 `auth` / `agent` / `video` / `media` 4 个领域的接口契约
- 所有领域暂时走前端 mock，等后端同学按 `docs/API.md` 逐个实现
- 前端 `USE_MOCK=true`，切换 `false` 即接通真后端

## 2026-08-01 agent · 新增积分校验与套餐

- 新增 `POST /api/agent/credits/check` → 发送前置积分校验
- 新增 `GET  /api/agent/plans` → 套餐列表
- 新增 `POST /api/agent/plans/orders` → 创建套餐订单拿支付链接
- 前端新增 `InsufficientCreditsDialog` 组件，4 档套餐卡（基础/标准/高级/企业）
- `agentApi` 增加 `checkCredits / listPlans / createPlanOrder` 3 个方法
- 发送消息流程：先 checkCredits → insufficient 时弹充值弹窗，不足时直接 return

## 2026-08-01 agent · 新增支付流程

- 新增 `GET  /api/agent/plans/orders/:id` → 订单状态轮询
- 新增 `POST /api/agent/plans/orders/:id/cancel` → 取消订单
- `CreatePlanOrderResponse` 新增 `qrCodeUrl / qrCodeContent / payMethod / expireAt / status` 字段
- 新增 `QueryOrderResponse` 订单查询响应 + `OrderStatus` 枚举
- 前端新增 `PaymentDialog` 通用支付弹窗：二维码 + 倒计时 + 状态轮询 + 成功提示
- `InsufficientCreditsDialog` 接入：点套餐 → 创建订单 → 自动打开支付弹窗
- `agentApi` 增加 `queryOrder / cancelOrder` 2 个方法
- Agent 页面 `InsufficientCreditsDialog` 收到 `onPaid` 时刷新积分

## 2026-08-01 agent · 新增企业客服联系

- 新增 `GET /api/agent/contact?scope=enterprise|general` → 客服联系方式
- 新增 `ContactInfoResponse / ContactChannel` 类型（支持微信/支付宝/邮箱/电话多渠道）
- 前端新增 `ContactDialog` 通用客服弹窗
- `InsufficientCreditsDialog` 接入：企业套餐点「联系客服」→ 自动打开客服弹窗
- 套餐卡片选中态改为"点击后变蓝"，默认 4 张卡都是普通白底
- `agentApi` 增加 `getContactInfo` 方法
- 后续换自己的二维码 → 后端改 `qrCodeUrl` 即可，前端零改动

## 2026-08-01 agent · 新增购买积分弹窗

- 新增 `GET /api/agent/credits/packages` → 积分包列表
- 新增 `POST /api/agent/credits/orders` → 创建积分充值订单
- 新增 `CreditPackage / CreateCreditsOrderRequest / CreateCreditsOrderResponse` 类型
- 前端新增 `BuyCreditsDialog` 9 档积分包弹窗
- `InsufficientCreditsDialog` 顶部「购买积分」链接改为按钮 → 触发 BuyCreditsDialog
- 购买积分的支付流程复用 `PaymentDialog`（二维码 + 倒计时 + 轮询）
- `agentApi` 增加 `listCreditPackages / createCreditsOrder` 2 个方法

## 2026-08-01 agent · 新增兑换充值卡

- 新增 `POST /api/agent/credits/redeem` → 兑换充值卡
- 新增 `RedeemCardRequest / RedeemCardResponse / RedeemCardErrorCode` 类型
- 前端新增 `RedeemCardDialog` 卡密输入弹窗（含成功态）
- `InsufficientCreditsDialog` 顶部「兑换充值卡」链接改为按钮
- 卡密**由后端生成**（客户付款 → 后台出码 → 兑换时对应金额入账），前端只负责输入 + 调接口
- `agentApi` 增加 `redeemCard` 方法

## 2026-08-01 product-image · 新增商详套图工作台

- 新增 `GET /api/product-image/models` / `settings` / `examples`
- 新增 `POST /api/product-image/tasks` → 提交商详套图任务
- 新增 `GET /api/product-image/tasks/:taskId` → 任务轮询
- 新增 `ProductImageModel / Setting / Example / Task / CreateProductImageRequest` 类型
- 重写 `/tools/product-image` 页面：左侧配置 + 中间参考示例轮播 + 右侧任务队列
- 首页 Hero「「一张图」生成一套商品详情图」按钮指向该页
- `productImageApi` 模块：`listModels / listSettings / listExamples / createTask / getTask`
- mock 用 Unsplash 占位图，轮询 1.5s 间隔，前端可立刻看到效果

## 2026-08-03 creations · 新增统一创作入口（三合一）

- 新增 `POST /api/creations` → 视频/图片生成任务（用 `type: 'video'|'image'` 区分）
- 新增 `GET /api/creations/:taskId` → 任务轮询
- 新增 `GET /api/creations?type=` → 任务列表
- 新增 `POST /api/agent/chat` → Agent 模式多轮对话（含工具调用 `actions`）
- 新增 `POST /api/agent/chat/stream` → Agent 流式回复（可选）
- 新增 `src/api/creations.{ts,mock.ts,real.ts}` —— 三合一创作接口（前端只调接口，模型调度由后端负责）
- 新增 `src/contexts/MaterialsContext.tsx` —— 全局素材库（所有工具页面共享）
- 新增 `src/components/common/InlineSelect.tsx` —— 小型内联下拉（带图标 / 描述 / 当前项 ✓）
- `ScriptCard` 重构：下拉选项改为 `video` / `image` / `agent`，提交按钮按 mode 调不同接口
- 注册 `creations` 到 `src/api/config.ts` 的 `APIS` 数组
- `MaterialsProvider` 提升到 `app/layout.tsx`，跨页面共享
