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
