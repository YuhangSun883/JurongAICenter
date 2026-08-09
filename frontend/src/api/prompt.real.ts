// 用户提示词 API —— 真实后端调用
import { request } from '@/lib/http';

const API = '/api/prompts';

/** 保存提示词请求 */
export interface SavePromptParams {
  title?: string;
  prompt: string;
}

/** 编辑提示词请求 */
export interface UpdatePromptParams {
  title?: string;
  prompt: string;
}

/** 提示词响应 */
export interface UserPromptResult {
  id: number;
  title: string;
  prompt: string;
  useCount: number;
  createdAt: string;
}

/**
 * 保存提示词（如果已存在则使用次数+1）
 */
export async function savePrompt(params: SavePromptParams): Promise<UserPromptResult> {
  return request<UserPromptResult>(API, {
    method: 'POST',
    body: params,
  });
}

/**
 * 编辑提示词
 */
export async function updatePrompt(id: number, params: UpdatePromptParams): Promise<UserPromptResult> {
  return request<UserPromptResult>(`${API}/${id}`, {
    method: 'PUT',
    body: params,
  });
}

/**
 * 获取当前用户的所有提示词（按使用次数降序）
 */
export async function listPrompts(): Promise<UserPromptResult[]> {
  return request<UserPromptResult[]>(API, {
    method: 'GET',
  });
}

/**
 * 使用提示词时调用，使用次数+1
 */
export async function usePrompt(id: number): Promise<{ success: boolean }> {
  return request<{ success: boolean }>(`${API}/${id}/use`, {
    method: 'PUT',
  });
}

/**
 * 删除提示词
 */
export async function deletePrompt(id: number): Promise<{ success: boolean }> {
  return request<{ success: boolean }>(`${API}/${id}`, {
    method: 'DELETE',
  });
}
