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

// 注意:本文件服务于 ai-video 工作台(/ai-video 页面),与画布(/canvas)视频生成无关。
// 画布视频生成走 canvas.real.ts → /api/canvas/nodes/{id}/generate-video。
// ai-video 独立工具项目前不在本次修复范围,保留原样。
const API = '/api/videos';
const JOBS_API = '/api/jobs';

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
  // 2026-08-13 修复:走 /api/jobs/{id}(后端实现),不是 /api/videos/{id}(不存在)
  const job = await request<{
    id: number;
    status: string;
    resultUrls: string[] | null;
    errorMessage: string | null;
    completedAt: string | null;
  }>(`${JOBS_API}/${id}`);
  // 适配为前端 VideoTask 格式
  return {
    id: String(job.id),
    status: normalizeStatus(job.status),
    progress: job.status === 'COMPLETED' ? 100 : job.status === 'FAILED' ? 0 : 50,
    request: {} as CreateVideoRequest,
    resultUrl: job.resultUrls?.[0],
    error: job.errorMessage ?? undefined,
    estimatedCredits: 0,
    createdAt: 0,
    updatedAt: job.completedAt ? new Date(job.completedAt).getTime() : Date.now(),
  };
}

function normalizeStatus(s: string): TaskStatus {
  // 后端大写 (RUNNING/COMPLETED/FAILED) → 前端小写 (queued/running/succeeded/failed)
  const u = s.toUpperCase();
  if (u === 'COMPLETED' || u === 'SUCCESS' || u === 'SUCCEEDED') return 'succeeded';
  if (u === 'FAILED' || u === 'ERROR' || u === 'CANCELLED') return 'failed';
  if (u === 'QUEUED' || u === 'PENDING') return 'queued';
  return 'running';
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
