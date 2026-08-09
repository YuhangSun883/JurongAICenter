// 创作任务 —— 真实后端实现（前端只做接口调用，具体逻辑由后端实现）
import { request } from '@/lib/http';
import type {
  CreateCreationRequest,
  CreationTask,
  AgentChatRequest,
  AgentChatResponse,
  CreationType,
} from './creations';

export function create(req: CreateCreationRequest): Promise<CreationTask> {
  return request<CreationTask>('/api/creations', { method: 'POST', body: req });
}

export function getTask(id: string): Promise<CreationTask> {
  return request<CreationTask>(`/api/creations/${id}`);
}

export function listTasks(q?: { type?: CreationType }): Promise<CreationTask[]> {
  return request<CreationTask[]>('/api/creations', {
    query: q as Record<string, string | number | boolean>,
  });
}

export function agentChat(req: AgentChatRequest): Promise<AgentChatResponse> {
  return request<AgentChatResponse>('/api/agent/chat', { method: 'POST', body: req });
}

/** 积分前置校验（具体校验逻辑由后端实现） */
export function checkCredits(req: {
  action: 'video-create' | 'image-create' | 'agent-chat';
  estimated?: number;
  context?: Record<string, unknown>;
}): Promise<import('./creations').CreditsCheckResponse> {
  return request<import('./creations').CreditsCheckResponse>('/api/agent/credits/check', {
    method: 'POST',
    body: req,
  });
}
