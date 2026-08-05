// 创作任务 —— 真实后端实现（前端只做接口调用，具体逻辑由后端实现）
import { request } from '@/lib/http';
import type {
  CreateCreationRequest,
  CreationTask,
  AgentChatRequest,
  AgentChatResponse,
  CreationType,
} from './creations';

/** 真实后端接口在 /api/generation/，前端 URL 适配 */
const API = '/api/generation';

export function create(req: CreateCreationRequest): Promise<CreationTask> {
  // 前端 CreationTask 与后端 GenerateResponse 结构有差异，使用 untyped 后转
  return request<CreationTask>(`${API}/generate`, { method: 'POST', body: req as unknown as Record<string, unknown> });
}

export function getTask(id: string): Promise<CreationTask> {
  return request<CreationTask>(`${API}/jobs/${id}`);
}

export function listTasks(q?: { type?: CreationType }): Promise<CreationTask[]> {
  return request<CreationTask[]>(`${API}/jobs`, {
    query: q as Record<string, string | number | boolean>,
  });
}

export function cancelTask(id: string): Promise<void> {
  return request<void>(`${API}/jobs/${id}`, { method: 'DELETE' });
}

/** Agent 模式 chat：复用 generate 接口，type=agent 在 body 标识 */
export function agentChat(req: AgentChatRequest): Promise<AgentChatResponse> {
  return request<AgentChatResponse>(`${API}/generate`, {
    method: 'POST',
    body: { ...req, type: 'agent' } as unknown as Record<string, unknown>,
  });
}

/** 积分前置校验：后端暂无对应接口，调用方需在 USE_MOCK=true 下走 mock */
export function checkCredits(req: {
  action: 'video-create' | 'image-create' | 'agent-chat';
  estimated?: number;
  context?: Record<string, unknown>;
}): Promise<import('./creations').CreditsCheckResponse> {
  // 后端 /api/agent/credits/check 暂未实现，先返回 mock 形式（业务方应判断）
  return Promise.reject(new Error('credits check not implemented in backend yet'));
}
