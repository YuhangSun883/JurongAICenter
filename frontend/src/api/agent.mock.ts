// 前端 mock：覆盖后端全部接口的本地假实现。USE_MOCK=true 时走这里。
// 后端就绪后只把 .env 改 false 即可，业务代码不动。

import type {
  AgentCreateSessionRequest,
  AgentCreateSessionResponse,
  AgentMessage,
  AgentMessageListQuery,
  AgentMessageListResponse,
  AgentRenameRequest,
  AgentSendRequest,
  AgentSendResponse,
  AgentSession,
  AgentSessionListQuery,
  AgentSessionListResponse,
  AgentCreditInfo,
  AgentTool,
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

/**
 * mock 演示用：默认 0 积分 → 用户上来就要充钱（弹 4 档套餐弹窗）
 * ⚠️ 这是 mock 专用配置；接真后端时改 .env 的 NEXT_PUBLIC_USE_MOCK=false，
 *    下面所有"积分计算/扣费"逻辑会被忽略，全部由后端算。
 *    想"充足积分"演示 → 改成 NEXT_PUBLIC_MOCK_CREDITS=9999
 */
const MOCK_TOTAL_CREDITS = Number(process.env.NEXT_PUBLIC_MOCK_CREDITS ?? 0);

const sessions = new Map<string, AgentSession>();
const messages = new Map<string, AgentMessage[]>();
const uid = (p: string) => `${p}_${Math.random().toString(36).slice(2, 10)}`;
const delay = <T>(v: T, ms = 200) => new Promise<T>((r) => setTimeout(() => r(v), ms));

// 工具 → 中文名映射
const TOOL_LABEL: Record<AgentTool, string> = {
  search: '联网搜索',
  web: '网页抓取',
  voice: '语音输入',
  kb: '知识库检索',
  skill: '调用技能',
};

function toolsUsedToCredits(tools: AgentTool[] | undefined) {
  return (tools?.length ?? 0) * 2 + 1;
}

export async function listSessions(q: AgentSessionListQuery = {}): Promise<AgentSessionListResponse> {
  let items = Array.from(sessions.values()).sort((a, b) => b.updatedAt - a.updatedAt);
  if (q.keyword) items = items.filter((s) => s.title.includes(q.keyword!));
  if (q.pinned !== undefined) items = items.filter((s) => !!s.pinned === q.pinned);
  const page = q.page ?? 1;
  const pageSize = q.pageSize ?? 20;
  const start = (page - 1) * pageSize;
  return delay({ items: items.slice(start, start + pageSize), total: items.length, page, pageSize });
}

export async function createSession(req: AgentCreateSessionRequest = {}): Promise<AgentCreateSessionResponse> {
  const now = Date.now();
  const s: AgentSession = {
    id: uid('s'),
    title: req.title?.trim() || '新对话',
    createdAt: now,
    updatedAt: now,
    creditsUsed: 0,
  };
  sessions.set(s.id, s);
  messages.set(s.id, []);
  return delay({ session: s }, 100);
}

export async function renameSession(req: AgentRenameRequest): Promise<AgentSession> {
  const s = sessions.get(req.sessionId);
  if (!s) throw new Error('session not found');
  s.title = req.title;
  s.updatedAt = Date.now();
  return delay({ ...s });
}

export async function deleteSession(sessionId: string): Promise<void> {
  sessions.delete(sessionId);
  messages.delete(sessionId);
}

export async function listMessages(q: AgentMessageListQuery): Promise<AgentMessageListResponse> {
  const arr = messages.get(q.sessionId) ?? [];
  return delay({ items: arr, total: arr.length, page: q.page ?? 1, pageSize: q.pageSize ?? 50 });
}

export async function send(req: AgentSendRequest): Promise<AgentSendResponse> {
  let sessionId = req.sessionId ?? null;
  // null / undefined / 空串 都视为"新对话"
  if (!sessionId) {
    const { session } = await createSession();
    sessionId = session.id;
  }
  let s = sessions.get(sessionId);
  // 兜底：如果 session 不存在（极端情况：被清了 / 别的进程），自动创建一个
  if (!s) {
    const { session } = await createSession({ title: '新对话' });
    sessionId = session.id;
    s = session;
  }
  const now = Date.now();
  const userMsg: AgentMessage = {
    id: uid('m'),
    sessionId,
    role: 'user',
    content: req.content,
    attachments: (req.attachmentIds ?? []).map((id) => ({
      id, type: 'image', url: `https://picsum.photos/seed/${id}/300`, name: id,
    })),
    toolCalls: (req.tools ?? []).map((t) => ({ key: t, label: TOOL_LABEL[t], status: 'done' as const })),
    createdAt: now,
  };
  const assistantMsg: AgentMessage = {
    id: uid('m'),
    sessionId,
    role: 'assistant',
    content: `我已收到你的输入：${req.content.slice(0, 40)}${req.content.length > 40 ? '...' : ''}\n（接口就绪后接入真实回复）`,
    createdAt: now + 1,
  };
  messages.set(sessionId, [...(messages.get(sessionId) ?? []), userMsg, assistantMsg]);
  s.updatedAt = now + 1;
  s.creditsUsed = (s.creditsUsed ?? 0) + toolsUsedToCredits(req.tools);
  if (s.title === '新对话' && req.content) {
    s.title = req.content.slice(0, 20);
  }
  return delay({
    sessionId,
    userMessageId: userMsg.id,
    assistantMessageId: assistantMsg.id,
    creditsUsed: s.creditsUsed,
    creditsEstimated: toolsUsedToCredits(req.tools),
  }, 200);
}

export async function sendStream(_req: AgentSendRequest): Promise<Response> {
  // mock 阶段不真流式，返回一个普通 Response
  return new Response(JSON.stringify({ mock: true }), { headers: { 'Content-Type': 'application/json' } });
}

export async function getCredits(): Promise<AgentCreditInfo> {
  const used = Array.from(sessions.values()).reduce((s, x) => s + (x.creditsUsed ?? 0), 0);
  return delay({
    total: MOCK_TOTAL_CREDITS,
    used,
    remaining: Math.max(0, MOCK_TOTAL_CREDITS - used),
  });
}

/* ============= 积分校验 mock ============= */
/**
 * mock 阶段：只要 estimated > 0 都通过
 * 真后端：会按账户余额 / 业务规则判断
 * 前端永远相信接口返回值
 */
export async function checkCredits(req: CreditsCheckRequest): Promise<CreditsCheckResponse> {
  const used = Array.from(sessions.values()).reduce((s, x) => s + (x.creditsUsed ?? 0), 0);
  const remaining = Math.max(0, MOCK_TOTAL_CREDITS - used);
  // 简单策略：剩余不足就返回 insufficient
  if (remaining < req.estimated) {
    return delay({
      status: 'insufficient',
      remaining,
      required: req.estimated,
      code: 40001,
      message: '积分不足，请充值或订阅套餐',
    });
  }
  return delay({ status: 'ok', remaining, required: req.estimated });
}

const MOCK_PLANS: PlanInfo[] = [
  {
    id: 'basic', title: '基础版（月卡）', badge: '9.6 折',
    price: 99, originalPrice: 103, description: '适合轻度体验',
    credits: 103, validDays: 30, cta: 'ghost',
    features: [
      '视频模型 Seedance 2.0 VIP 通道',
      '高级图片模型',
      '智能编导·一键商详·无限画布',
      '电脑/手机多端可用',
    ],
  },
  {
    id: 'standard', title: '标准版（月卡）', badge: '9 折',
    price: 399, originalPrice: 443, description: '更划算，适合持续创作',
    credits: 443, validDays: 30, cta: 'primary', highlighted: true,
    features: [
      '视频模型 Seedance 2.0 VIP 通道',
      '高级图片模型',
      '智能编导·一键商详·无限画布',
      '电脑/手机多端可用',
    ],
  },
  {
    id: 'premium', title: '高级版（月卡）', badge: '8.3 折',
    price: 699, originalPrice: 838, description: '单价更低，适合高强度创作',
    credits: 838, validDays: 30, cta: 'ghost',
    features: [
      '视频模型 Seedance 2.0 VIP 通道',
      '高级图片模型',
      '智能编导·一键商详·无限画布',
      '电脑/手机多端可用',
    ],
  },
  {
    id: 'enterprise', title: '企业套餐', description: '联系客服',
    price: 0, credits: 0, validDays: 0, cta: 'contact',
    features: [
      '企业用量与能力可单独报价',
      '支持合同、对公与开票',
      '提供企业内训和业务陪跑',
      '资产存储数量与权限可按需定制',
    ],
  },
];

export async function listPlans(): Promise<PlanInfo[]> {
  return delay(MOCK_PLANS);
}

/* 订单状态 mock 存储 —— 必须先于 createPlanOrder */
const ORDER_STORE = new Map<string, { planId: string; amount: number; expireAt: number; status: 'pending' | 'paid' | 'cancelled' | 'expired' }>();

export async function createPlanOrder(req: CreatePlanOrderRequest): Promise<CreatePlanOrderResponse> {
  const plan = MOCK_PLANS.find((p) => p.id === req.planId);
  const orderId = 'order_' + Math.random().toString(36).slice(2, 10);
  const payMethod = req.payMethod ?? 'alipay';
  const expireAt = Date.now() + 15 * 60 * 1000;
  ORDER_STORE.set(orderId, { planId: req.planId, amount: plan?.price ?? 0, expireAt, status: 'pending' });
  return delay({
    orderId,
    payUrl: `https://pay.example.com/checkout?order=${orderId}`,
    amount: plan?.price ?? 0,
    payMethod,
    qrCodeUrl: 'https://api.qrserver.com/v1/create-qr-code/?size=240x240&data=' + encodeURIComponent(`mock://pay?order=${orderId}&amount=${plan?.price ?? 0}`),
    expireAt,
    status: 'pending',
  });
}

export async function queryOrder(orderId: string): Promise<QueryOrderResponse> {
  const order = ORDER_STORE.get(orderId);
  if (!order) {
    return delay({ orderId, status: 'expired' as const, amount: 0, planId: '' });
  }
  if (order.status === 'pending' && Date.now() > order.expireAt) {
    order.status = 'expired';
  }
  // 演示：随机 5% 概率直接 paid（让前端看到成功态）
  if (order.status === 'pending' && Math.random() < 0.05) {
    order.status = 'paid';
  }
  return delay({
    orderId,
    status: order.status,
    amount: order.amount,
    planId: order.planId,
    paidAt: order.status === 'paid' ? Date.now() : undefined,
    receipt: order.status === 'paid' ? { creditsAdded: 0, validDays: 30 } : undefined,
  });
}

export async function cancelOrder(orderId: string): Promise<void> {
  const o = ORDER_STORE.get(orderId);
  if (o && o.status === 'pending') o.status = 'cancelled';
}

/* ============= 客服咨询 mock ============= */
export async function getContactInfo(_scope?: 'enterprise' | 'general'): Promise<ContactInfoResponse> {
  return delay({
    title: '企业套餐咨询',
    description: '扫码添加客服，咨询企业充值与定制方案。',
    channels: [
      {
        method: 'wechat',
        qrCodeUrl: 'https://api.qrserver.com/v1/create-qr-code/?size=300x300&data=' + encodeURIComponent('weixin://contacts?username=jurong_kefu'),
        description: '扫码添加，咨询充值',
      },
    ],
    footerHint: '扫码添加，咨询充值',
  });
}

/* ============= 购买积分 mock ============= */
const CREDIT_PACKAGES: CreditPackage[] = [
  { id: 'pkg-50', price: 50, credits: 50 },
  { id: 'pkg-75', price: 75, credits: 75, highlighted: true },
  { id: 'pkg-150', price: 150, credits: 150 },
  { id: 'pkg-225', price: 225, credits: 225 },
  { id: 'pkg-450', price: 450, credits: 450 },
  { id: 'pkg-882', price: 882, credits: 900 },
  { id: 'pkg-1960', price: 1960, credits: 2000 },
  { id: 'pkg-4900', price: 4900, credits: 5000 },
  { id: 'pkg-9800', price: 9800, credits: 10000 },
];

export async function listCreditPackages(): Promise<CreditPackage[]> {
  return delay(CREDIT_PACKAGES);
}

export async function createCreditsOrder(req: CreateCreditsOrderRequest): Promise<CreateCreditsOrderResponse> {
  const pkg = CREDIT_PACKAGES.find((p) => p.id === req.packageId);
  const orderId = 'cord_' + Math.random().toString(36).slice(2, 10);
  const payMethod = req.payMethod ?? 'alipay';
  const expireAt = Date.now() + 15 * 60 * 1000;
  ORDER_STORE.set(orderId, {
    planId: req.packageId, // 也存到订单表，方便 queryOrder 复用
    amount: pkg?.price ?? 0,
    expireAt,
    status: 'pending',
  });
  return delay({
    orderId,
    payUrl: `https://pay.example.com/checkout?order=${orderId}`,
    amount: pkg?.price ?? 0,
    credits: pkg?.credits ?? 0,
    payMethod,
    qrCodeUrl: 'https://api.qrserver.com/v1/create-qr-code/?size=240x240&data=' + encodeURIComponent(`mock://pay?order=${orderId}&amount=${pkg?.price ?? 0}`),
    expireAt,
    status: 'pending',
  });
}

/* ============= 兑换充值卡 mock ============= */
/**
 * mock 行为：
 *   - 任何格式合法的卡密都返回成功 + 随机积分（100~1000）
 *   - 真后端：会用卡密对应的金额入账
 */
export async function redeemCard(req: RedeemCardRequest): Promise<RedeemCardResponse> {
  const code = req.code.trim();
  // 简单格式校验：XX-XXXX-XXXX-XXXX 形式
  if (!/^[A-Z]{2}-[A-Z0-9]{4}-[A-Z0-9]{4}-[A-Z0-9]{4}$/.test(code)) {
    throw new Error('卡密格式错误，请输入正确的卡密（格式：XX-XXXX-XXXX-XXXX）');
  }
  const creditsAdded = 100 + Math.floor(Math.random() * 900); // 100~999
  return delay({
    creditsAdded,
    validDays: 365,
    redeemId: 'rc_' + Math.random().toString(36).slice(2, 10),
    remainingCredits: creditsAdded,
  });
}

// 注：上面的 createPlanOrder 已直接写入 ORDER_STORE，
// 业务侧通过 agentApi.createPlanOrder 调用，状态轮询走 queryOrder
