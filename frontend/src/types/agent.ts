// Agent 聊天 —— 全部接口契约集中在这里
// 后端同学按此实现，前端按此调用。mock / real 切换见 src/api/agent.ts

import type { PageQuery, PageResult } from './api';

/** 会话（一个对话窗口） */
export interface AgentSession {
  id: string;
  title: string;
  pinned?: boolean;
  createdAt: number;
  updatedAt: number;
  /** 当前会话已消耗的积分 */
  creditsUsed?: number;
}

/** 消息 */
export interface AgentMessage {
  id: string;
  sessionId: string;
  role: 'user' | 'assistant' | 'system';
  content: string;
  /** 消息附带的素材 */
  attachments?: Array<{
    id: string;
    type: 'image' | 'video' | 'audio';
    url: string;
    name: string;
  }>;
  /** 工具调用（联网搜索/技能等） */
  toolCalls?: Array<{
    key: string;
    label: string;
    status: 'pending' | 'done' | 'failed';
  }>;
  createdAt: number;
}

/** 工具 / 技能开关 */
export type AgentTool = 'search' | 'web' | 'voice' | 'kb' | 'skill';

/** 发送消息请求 */
export interface AgentSendRequest {
  sessionId: string | null; // null = 新对话
  content: string;
  attachmentIds?: string[];
  tools?: AgentTool[];
  /** 选中的角色库角色 id */
  roleIds?: string[];
}

/** 发送消息响应：返回用户消息 + AI 占位（流式） */
export interface AgentSendResponse {
  sessionId: string;
  userMessageId: string;
  assistantMessageId: string;
  /** 首屏展示用的累计积分 */
  creditsUsed: number;
  /** 预估本轮消耗（流式完成后会被实际值覆盖） */
  creditsEstimated: number;
  /** LLM 决定的工具调用（可选）。存在时前端弹确认框让用户决定是否跳转。 */
  toolCall?: AgentToolCall;
}

/** LLM 工具调用（Agent 返回的跳转建议） */
export interface AgentToolCall {
  /** 动作名（jump-to-image / jump-to-video / jump-to-image-edit / ...） */
  action: string;
  /** 用户意图文本（会作为目标模块的 prompt 预填） */
  prompt: string;
  /** AI 简短解释（可空） */
  reason?: string;
  /** 携带的素材 ID（前端跳转时作为 query 参数） */
  attachmentIds?: string[];
}

/** 列表查询会话 */
export type AgentSessionListQuery = PageQuery & {
  pinned?: boolean;
  keyword?: string;
};

export type AgentSessionListResponse = PageResult<AgentSession>;

/** 列表查询某会话的消息 */
export type AgentMessageListQuery = PageQuery & {
  sessionId: string;
};

export type AgentMessageListResponse = PageResult<AgentMessage>;

/** 创建会话请求 */
export interface AgentCreateSessionRequest {
  title?: string;
}

export interface AgentCreateSessionResponse {
  session: AgentSession;
}

/** 重命名会话 */
export interface AgentRenameRequest {
  sessionId: string;
  title: string;
}

/** 当前用户积分 */
export interface AgentCreditInfo {
  total: number;       // 总积分
  used: number;        // 累计已消耗
  remaining: number;   // 剩余
  /** 本次消息预估消耗 */
  estimated?: number;
}

/* ================= 积分前置校验（发送前调用） ================= */

/** 发送前问后端"够不够" */
export interface CreditsCheckRequest {
  /** 本次操作需要的动作类型 */
  action: 'agent-send' | 'video-create' | 'image-create';
  /** 这次要消耗多少（前端粗算；后端为准） */
  estimated: number;
  /** 影响的参数：例如消息长度、是否带工具、模型等 */
  context?: Record<string, string | number | boolean>;
}

export type CreditsCheckStatus = 'ok' | 'insufficient' | 'unknown';

export interface CreditsCheckResponse {
  status: CreditsCheckStatus;
  /** 剩余积分（status=ok 或 insufficient 都会回） */
  remaining: number;
  /** 本次操作实际需要（后端精算值，前端用它替换 estimated） */
  required: number;
  /** 错误码（status=insufficient 时有） */
  code?: number;
  /** 文案（status=insufficient 时有） */
  message?: string;
  /** 升级套餐链接（status=insufficient 时有，前端可跳） */
  upgradeUrl?: string;
}

/* ================= 套餐 / 订单 ================= */

export interface PlanInfo {
  id: string;
  title: string;
  badge?: string;
  price: number;
  originalPrice?: number;
  description: string;
  credits: number;
  validDays: number;
  features: string[];
  highlighted?: boolean;
  /** 'primary' | 'ghost' | 'contact' */
  cta: 'primary' | 'ghost' | 'contact';
}

export interface CreatePlanOrderRequest {
  planId: string;
  /** 支付方式：'wechat' | 'alipay' | 'card' */
  payMethod?: 'wechat' | 'alipay' | 'card';
}

export type PayMethod = 'wechat' | 'alipay' | 'card';

export type OrderStatus =
  | 'pending'     // 待支付
  | 'paid'        // 已支付
  | 'cancelled'   // 已取消
  | 'expired'     // 已过期
  | 'refunded';   // 已退款

export interface CreatePlanOrderResponse {
  orderId: string;
  payUrl: string;
  amount: number;
  /** 二维码图片地址（前端直接 <img> 展示，常见于支付宝扫码） */
  qrCodeUrl?: string;
  /** 二维码内容（前端自行生成二维码图片，可与 qrCodeUrl 二选一） */
  qrCodeContent?: string;
  /** 支付方式 */
  payMethod: PayMethod;
  /** 过期时间戳（毫秒） */
  expireAt: number;
  /** 订单状态 */
  status: OrderStatus;
}

/* ============= 客服咨询（企业套餐用） ============= */

/** 客服联系方式（每种支付/渠道一张二维码） */
export interface ContactChannel {
  /** 'wechat' | 'alipay' | 'card' | 'phone' | 'email' */
  method: PayMethod | 'phone' | 'email';
  /** 二维码图片地址 */
  qrCodeUrl?: string;
  /** 二维码内容（与 qrCodeUrl 二选一） */
  qrCodeContent?: string;
  /** 纯文本联系方式（手机号 / 邮箱） */
  value?: string;
  /** 描述文案，如 "扫码添加客服，咨询充值" */
  description?: string;
}

export interface ContactInfoResponse {
  /** 弹窗主标题，如 "企业套餐咨询" */
  title: string;
  /** 弹窗副标题 */
  description: string;
  /** 多个联系方式（一般至少 1 个） */
  channels: ContactChannel[];
  /** 底部提示语 */
  footerHint?: string;
}

/* ============= 购买积分（一次性充值） ============= */

/** 积分包（一次性购买，不与订阅会员挂钩） */
export interface CreditPackage {
  id: string;
  /** 价格（元） */
  price: number;
  /** 积分数量 */
  credits: number;
  /** 是否推荐（后端可高亮某一个） */
  highlighted?: boolean;
}

export interface CreateCreditsOrderRequest {
  packageId: string;
  payMethod?: PayMethod;
}

export interface CreateCreditsOrderResponse {
  orderId: string;
  payUrl: string;
  amount: number;
  credits: number;
  qrCodeUrl?: string;
  qrCodeContent?: string;
  payMethod: PayMethod;
  expireAt: number;
  status: OrderStatus;
}

/* ============= 兑换充值卡 ============= */

/** 兑换充值卡请求 */
export interface RedeemCardRequest {
  /** 卡密字符串，格式示例：JR-XXXX-XXXX-XXXX */
  code: string;
}

/** 兑换成功响应 */
export interface RedeemCardResponse {
  /** 入账的积分数 */
  creditsAdded: number;
  /** 赠送有效期（天数） */
  validDays: number;
  /** 兑换流水号（用于查询 / 售后） */
  redeemId: string;
  /** 兑换后用户剩余积分（前端刷新用） */
  remainingCredits: number;
}

export type RedeemCardErrorCode =
  | 'invalid_code'    // 卡密格式错
  | 'not_found'       // 卡密不存在
  | 'used'            // 卡密已使用
  | 'expired'         // 卡密过期
  | 'locked'          // 卡密被锁定（多次输入错误）
  | 'server_error';   // 服务端错误

export interface RedeemCardErrorResponse {
  error: RedeemCardErrorCode;
  message: string;
}

/* ============= 订单查询 ============= */

export interface QueryOrderResponse {
  orderId: string;
  status: OrderStatus;
  /** 支付成功的时间 */
  paidAt?: number;
  /** 失败原因 */
  failReason?: string;
  amount: number;
  planId: string;
  /** 支付成功后的回执（积分入账等） */
  receipt?: {
    creditsAdded: number;
    validDays: number;
  };
}
