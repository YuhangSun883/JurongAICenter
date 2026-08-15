// 2026-08-15 新增:画质增强 API 客户端
// 后端端点:
//   POST /api/image-enhancer/submit   — 提交任务
//   GET  /api/image-enhancer/jobs/{id} — 轮询任务状态
import { request } from '@/lib/http';

export interface EnhancerSubmitRequest {
  videoUrl: string;
  version: string;
  setting: string;
}

export interface EnhancerSubmitResponse {
  taskId: string;
  status: string;
}

export interface EnhancerJobResponse {
  taskId: string;
  status: string;
  videoUrl?: string;
  outputUrl?: string;
  errorMessage?: string;
  createdAt?: number;
  completedAt?: number;
}

export function submitEnhance(body: EnhancerSubmitRequest): Promise<EnhancerSubmitResponse> {
  return request<EnhancerSubmitResponse>('/api/image-enhancer/submit', {
    method: 'POST',
    body,
  });
}

export function getEnhanceJob(taskId: string): Promise<EnhancerJobResponse> {
  return request<EnhancerJobResponse>(`/api/image-enhancer/jobs/${encodeURIComponent(taskId)}`);
}
