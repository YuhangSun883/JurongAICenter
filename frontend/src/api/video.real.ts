// 视频生成 - 真实后端实现
// 注意：后端实际路径是 /api/generation/*，前端 URL 适配
import { request } from '@/lib/http';
import type {
  CreateVideoRequest,
  CreateVideoResponse,
  GenerateVideoScriptRequest,
  GenerateVideoScriptResponse,
  ListTasksQuery,
  ListTasksResponse,
  VideoTask,
} from '@/types/video';

const API = '/api/generation';

export async function generateScript(
  req: GenerateVideoScriptRequest
): Promise<GenerateVideoScriptResponse> {
  // 后端暂无 /api/videos/script，保持调用即可（USE_MOCK=true 时由 mock 处理）
  return Promise.reject(new Error('generateScript not implemented in backend yet'));
}

export async function create(req: CreateVideoRequest): Promise<CreateVideoResponse> {
  return request<CreateVideoResponse>(`${API}/generate`, { method: 'POST', body: req as unknown as Record<string, unknown> });
}

export async function getTask(id: string): Promise<VideoTask> {
  return request<VideoTask>(`${API}/jobs/${id}`);
}

export async function listTasks(q: ListTasksQuery = {}): Promise<ListTasksResponse> {
  return request<ListTasksResponse>(`${API}/jobs`, { query: q as Record<string, string | number> });
}

export async function cancel(id: string): Promise<void> {
  // 后端是 DELETE /api/generation/jobs/{id}，不是 POST /cancel
  await request<void>(`${API}/jobs/${id}`, { method: 'DELETE' });
}

export async function retry(_id: string): Promise<CreateVideoResponse> {
  // 后端暂无 /api/videos/{id}/retry 接口
  return Promise.reject(new Error('retry not implemented in backend yet'));
}
