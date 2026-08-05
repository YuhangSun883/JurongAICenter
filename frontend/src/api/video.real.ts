// 视频生成 - 真实后端实现
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

const API = '/api/videos';

export async function generateScript(
  req: GenerateVideoScriptRequest
): Promise<GenerateVideoScriptResponse> {
  return request<GenerateVideoScriptResponse>(`${API}/script`, {
    method: 'POST',
    body: req,
  });
}

export async function create(req: CreateVideoRequest): Promise<CreateVideoResponse> {
  return request<CreateVideoResponse>(API, { method: 'POST', body: req });
}

export async function getTask(id: string): Promise<VideoTask> {
  return request<VideoTask>(`${API}/${id}`);
}

export async function listTasks(q: ListTasksQuery = {}): Promise<ListTasksResponse> {
  return request<ListTasksResponse>(API, { query: q as Record<string, string | number> });
}

export async function cancel(id: string): Promise<void> {
  await request<void>(`${API}/${id}/cancel`, { method: 'POST' });
}

export async function retry(id: string): Promise<CreateVideoResponse> {
  return request<CreateVideoResponse>(`${API}/${id}/retry`, { method: 'POST' });
}
