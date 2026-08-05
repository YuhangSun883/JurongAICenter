// 创作任务统一入口（视频生成 / 图片生成 / Agent 模式）
import { USE_MOCK } from './config';
import * as mock from './creations.mock';
import * as real from './creations.real';

export type CreationType = 'video' | 'image' | 'agent';

export interface CreateCreationRequest {
  type: CreationType;
  /** 提示词 / 脚本 */
  prompt: string;
  /** 引用的素材 id（来自 MediaPickerDialog） */
  materialIds?: string[];
  /** Agent 模式下用于多轮对话 */
  conversationId?: string;
  /** 可选模型 key（默认走后端默认） */
  modelKey?: string;
}

export interface CreationTask {
  taskId: string;
  type: CreationType;
  status: 'pending' | 'running' | 'success' | 'failed';
  /** 不同 type 返回不同字段 */
  resultUrl?: string;
  text?: string;
  failReason?: string;
  createdAt: number;
}

export interface AgentChatRequest {
  conversationId?: string;
  message: string;
  materialIds?: string[];
}

export interface AgentChatResponse {
  conversationId: string;
  reply: string;
  /** Agent 可能调用的工具 / 动作 */
  actions?: { name: string; status: 'ok' | 'fail'; result?: unknown }[];
}

/** 积分校验请求（具体校验逻辑由后端实现） */
export interface CreditsCheckRequest {
  action: 'video-create' | 'image-create' | 'agent-chat';
  estimated?: number;
  context?: Record<string, unknown>;
}

/** 积分校验响应（具体状态由后端实现） */
export interface CreditsCheckResponse {
  status: 'ok' | 'insufficient' | 'unknown';
  remaining: number;
  required: number;
  code?: string;
  message?: string;
  upgradeUrl?: string;
}

export const creationsApi = {
  create: (req: CreateCreationRequest): Promise<CreationTask> =>
    USE_MOCK ? mock.create(req) : real.create(req),

  getTask: (id: string): Promise<CreationTask> =>
    USE_MOCK ? mock.getTask(id) : real.getTask(id),

  agentChat: (req: AgentChatRequest): Promise<AgentChatResponse> =>
    USE_MOCK ? mock.agentChat(req) : real.agentChat(req),

  listTasks: (q?: { type?: CreationType }): Promise<CreationTask[]> =>
    USE_MOCK ? mock.listTasks(q) : real.listTasks(q),

  /** 取消任务（DELETE /api/generation/jobs/{id}） */
  cancelTask: (id: string): Promise<void> =>
    USE_MOCK ? mock.cancelTask(id) : real.cancelTask(id),

  /** 积分前置校验（前端可选调用，由后端决定是否真校验） */
  checkCredits: (req: CreditsCheckRequest): Promise<CreditsCheckResponse> =>
    USE_MOCK ? mock.checkCredits(req) : real.checkCredits(req),
};

export type { ProductImageTask } from '@/types/product-image';