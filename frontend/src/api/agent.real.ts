// 真实后端实现 —— 后端给接口后，按此改 URL 即可
import { request } from '@/lib/http';
import { getAccessToken } from '@/lib/auth-store';
import type {
  AgentCreateSessionRequest,
  AgentCreateSessionResponse,
  AgentMessageListQuery,
  AgentMessageListResponse,
  AgentRenameRequest,
  AgentSendRequest,
  AgentSendResponse,
  AgentSessionListQuery,
  AgentSessionListResponse,
  AgentSession,
  AgentMessage,
  AgentCreditInfo,
  CreditsCheckRequest,
  CreditsCheckResponse,
  PlanInfo,
  CreatePlanOrderRequest,
  CreatePlanOrderResponse,
  QueryOrderResponse,
  ContactInfoResponse,
  CreditPackage,
  CreateCreditsOrderRequest,
  CreateCreditsOrderResponse,
  RedeemCardRequest,
  RedeemCardResponse,
} from '@/types/agent';

/** 拉会话列表 */
export async function listSessions(q: AgentSessionListQuery = {}): Promise<AgentSessionListResponse> {
  return request<AgentSessionListResponse>('/api/agent/sessions', { query: q as Record<string, string | number | boolean> });
}

/** 创建新会话 */
export async function createSession(req: AgentCreateSessionRequest = {}): Promise<AgentCreateSessionResponse> {
  return request<AgentCreateSessionResponse>('/api/agent/sessions', { method: 'POST', body: req });
}

/** 重命名会话 */
export async function renameSession(req: AgentRenameRequest): Promise<AgentSession> {
  return request<AgentSession>(`/api/agent/sessions/${req.sessionId}`, {
    method: 'PATCH', body: { title: req.title },
  });
}

/** 删除会话 */
export async function deleteSession(sessionId: string): Promise<void> {
  await request<void>(`/api/agent/sessions/${sessionId}`, { method: 'DELETE' });
}

/** 拉某会话的消息列表 */
export async function listMessages(q: AgentMessageListQuery): Promise<AgentMessageListResponse> {
  return request<AgentMessageListResponse>(`/api/agent/sessions/${q.sessionId}/messages`, {
    query: { page: q.page, pageSize: q.pageSize },
  });
}

/** 发送消息（同步版：等待 AI 完整回复） */
export async function send(req: AgentSendRequest): Promise<AgentSendResponse> {
  return request<AgentSendResponse>('/api/agent/send', { method: 'POST', body: req });
}

/** 发送消息（流式版：返回 ReadableStream，前端逐块解析） */
export async function sendStream(req: AgentSendRequest): Promise<Response> {
  // 2026-08-14 修复:必须带 Authorization,否则后端返回 401
  // 不能走 request(),因为 SSE 响应不能被消费 body;这里自己拼 fetch + token
  const token = getAccessToken();
  const headers: Record<string, string> = { 'Content-Type': 'application/json' };
  if (token) headers.Authorization = `Bearer ${token}`;
  // 2026-08-14 关键修复:Next.js dev server 的 rewrites 会 buffer 整个 SSE 响应,
  //   导致前端 reader 一直阻塞("正在思考")。必须绕过 dev server,直连后端 8080。
  //   生产环境用 nginx 不会有这个问题,但保险起见也走绝对 URL。
  const apiBase = process.env.NEXT_PUBLIC_API_BASE_URL || 'http://localhost:8080';
  return fetch(`${apiBase}/api/agent/send/stream`, {
    method: 'POST',
    headers,
    body: JSON.stringify(req),
  });
}

/** 查询当前用户积分 */
export async function getCredits(): Promise<AgentCreditInfo> {
  return request<AgentCreditInfo>('/api/agent/credits');
}

/**
 * 发送前积分校验（推荐在 send 之前调一次）
 * - 后端根据 action + estimated + context 精算 required
 * - 返回 ok：可直接调 send
 * - 返回 insufficient：弹充值弹窗
 */
export async function checkCredits(req: CreditsCheckRequest): Promise<CreditsCheckResponse> {
  return request<CreditsCheckResponse>('/api/agent/credits/check', { method: 'POST', body: req });
}

/** 套餐列表（弹窗里 4 档套餐的数据源） */
export async function listPlans(): Promise<PlanInfo[]> {
  return request<PlanInfo[]>('/api/agent/plans');
}

/** 选完套餐后，创建订单并拿支付链接 */
export async function createPlanOrder(req: CreatePlanOrderRequest): Promise<CreatePlanOrderResponse> {
  return request<CreatePlanOrderResponse>('/api/agent/plans/orders', { method: 'POST', body: req });
}

/** 轮询订单状态（前端用这个判断是否已支付） */
export async function queryOrder(orderId: string): Promise<QueryOrderResponse> {
  return request<QueryOrderResponse>(`/api/agent/plans/orders/${orderId}`);
}

/** 主动取消订单 */
export async function cancelOrder(orderId: string): Promise<void> {
  await request<void>(`/api/agent/plans/orders/${orderId}/cancel`, { method: 'POST' });
}

/**
 * 客服联系方式（企业套餐弹窗 / 通用客服入口）
 * 后端返回多渠道信息，前端通用渲染：二维码 / 手机号 / 邮箱
 */
export async function getContactInfo(scope?: 'enterprise' | 'general'): Promise<ContactInfoResponse> {
  return request<ContactInfoResponse>('/api/agent/contact', {
    query: scope ? { scope } : undefined,
  });
}

/* ============= 购买积分（一次性充值） ============= */

/** 积分包列表（购买积分弹窗） */
export async function listCreditPackages(): Promise<CreditPackage[]> {
  return request<CreditPackage[]>('/api/agent/credits/packages');
}

/** 创建积分充值订单 → 拿到支付二维码 */
export async function createCreditsOrder(req: CreateCreditsOrderRequest): Promise<CreateCreditsOrderResponse> {
  return request<CreateCreditsOrderResponse>('/api/agent/credits/orders', { method: 'POST', body: req });
}

/**
 * 兑换充值卡
 * 后端做的事：卡密查 → 校验（未使用/未过期）→ 标记已用 → 入账积分
 * 成功返回 RedeemCardResponse；失败后端可能 throw 或返回 error 字段
 */
export async function redeemCard(req: RedeemCardRequest): Promise<RedeemCardResponse> {
  return request<RedeemCardResponse>('/api/agent/credits/redeem', { method: 'POST', body: req });
}
