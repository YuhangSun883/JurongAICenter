// 视频生成 - 真实后端实现
import { request, ApiError } from '@/lib/http';
import { getAccessToken } from '@/lib/auth-store';
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
const API_BASE = process.env.NEXT_PUBLIC_API_BASE_URL ?? 'http://localhost:4000';

/** 根据创建时间模拟任务的进度（PENDING 0%→10%，RUNNING 10%→90%） */
function simulateProgress(job: any, status: string): number {
  if (!job || !job.createdAt) {
    // 没有创建时间也给个初始进度
    if (status === 'RUNNING') return 10;
    if (status === 'PENDING') return 5;
    return 0;
  }
  const createdAt = new Date(job.createdAt).getTime();
  const elapsed = Date.now() - createdAt;

  if (status === 'PENDING') {
    // PENDING 阶段：0% → 10%（用于显示"正在提交/排队中"的反馈）
    if (elapsed < 30_000) return Math.round((elapsed / 30_000) * 10);
    return 10;
  }

  if (status === 'RUNNING') {
    // RUNNING 阶段：10% → 90%
    // 前 30 秒：10% → 50%
    // 30-120 秒：50% → 80%
    // 120+ 秒：80% → 90%（上限）
    if (elapsed < 30_000) return 10 + Math.round((elapsed / 30_000) * 40);
    if (elapsed < 120_000) return 50 + Math.round(((elapsed - 30_000) / 90_000) * 30);
    return 80 + Math.round(Math.min(10, ((elapsed - 120_000) / 180_000) * 10));
  }

  return 0;
}

/** 后端 JobResponse → 前端 VideoTask 格式转换 */
function mapJobToTask(job: any): VideoTask {
  if (!job) {
    console.warn('[mapJobToTask] job is null/undefined');
    return {
      id: '',
      status: 'queued',
      progress: 0,
      request: {} as CreateVideoRequest,
      estimatedCredits: 0,
      createdAt: Date.now(),
      updatedAt: Date.now(),
    };
  }

  const statusMap: Record<string, VideoTask['status']> = {
    PENDING: 'queued',
    RUNNING: 'running',
    COMPLETED: 'succeeded',
    FAILED: 'failed',
    CANCELLED: 'failed',
    DELETED: 'failed',
  };

  const rawStatus = String(job.status || '').toUpperCase();
  const mappedStatus = statusMap[rawStatus] || 'queued';

  // 解析 resultUrls（后端可能返回 JSON 字符串或数组）
  let resultUrl: string | undefined;
  if (Array.isArray(job.resultUrls) && job.resultUrls.length > 0) {
    resultUrl = job.resultUrls[0];
  } else if (typeof job.resultUrls === 'string' && job.resultUrls) {
    try {
      const arr = JSON.parse(job.resultUrls);
      if (Array.isArray(arr) && arr.length > 0) resultUrl = arr[0];
    } catch { /* ignore */ }
  }

  // 进度：COMPLETED → 100, FAILED → 0, RUNNING → 10-90%, PENDING → 0-10%
  let progress: number;
  if (rawStatus === 'COMPLETED') {
    progress = 100;
  } else if (rawStatus === 'FAILED' || rawStatus === 'CANCELLED' || rawStatus === 'DELETED') {
    progress = 0;
  } else if (rawStatus === 'RUNNING') {
    progress = simulateProgress(job, 'RUNNING');
  } else if (rawStatus === 'PENDING') {
    progress = simulateProgress(job, 'PENDING');
  } else {
    progress = 0;
  }

  const task: VideoTask = {
    id: String(job.id ?? ''),
    status: mappedStatus,
    progress,
    request: {} as CreateVideoRequest,
    resultUrl,
    error: job.errorMessage,
    estimatedCredits: job.creditsCost || 0,
    createdAt: job.createdAt ? new Date(job.createdAt).getTime() : Date.now(),
    updatedAt: job.completedAt ? new Date(job.completedAt).getTime() : Date.now(),
  };

  console.debug('[mapJobToTask]', { rawStatus, mappedStatus, progress, jobId: job.id });
  return task;
}

export async function generateScript(
  req: GenerateVideoScriptRequest
): Promise<GenerateVideoScriptResponse> {
  return request<GenerateVideoScriptResponse>(`${API}/script`, {
    method: 'POST',
    body: req,
  });
}

export async function create(req: CreateVideoRequest): Promise<CreateVideoResponse> {
  const resp = await request<any>(API, { method: 'POST', body: req });
  // request 函数已解包 {code, data}，resp 是业务对象 {jobId, status, ...}
  const taskId = String(resp?.jobId ?? resp?.id ?? '');
  if (!taskId || taskId === 'undefined') {
    throw new Error('后端未返回有效的 jobId，响应: ' + JSON.stringify(resp));
  }
  return { taskId };
}

/**
 * 图生视频 — 调用后端 POST /api/video/image-to-video (multipart)
 * 下载图片 URL → 构建 FormData → 发送到后端
 */
export async function createImageToVideo(
  imageUrl: string,
  prompt: string,
  duration: number,
  resolution: string
): Promise<CreateVideoResponse> {
  // 下载图片为 Blob
  const blob = await (await fetch(imageUrl)).blob();
  const file = new File([blob], 'reference.jpg', { type: blob.type || 'image/jpeg' });

  // 构建 multipart FormData
  const formData = new FormData();
  formData.append('file', file);
  formData.append('prompt', prompt);
  formData.append('duration', String(duration));
  formData.append('resolution', resolution);

  // 发送到后端
  const token = getAccessToken();
  const resp = await fetch(`${API_BASE}/api/video/image-to-video`, {
    method: 'POST',
    headers: { Authorization: `Bearer ${token}` },
    body: formData,
  });

  if (!resp.ok) {
    let payload: unknown = undefined;
    try { payload = await resp.json(); } catch { /* ignore */ }
    throw new ApiError(resp.status, `HTTP ${resp.status}`, payload);
  }

  const raw = await resp.json();
  // 后端可能返回统一包装 {code, message, data} 或直接返回业务对象 {jobId, status, ...}
  if (raw && typeof raw === 'object' && 'code' in raw) {
    // 有 code 字段 → 是统一包装格式
    if (raw.code !== 0 && raw.code !== 200) {
      // 业务错误，抛出后端错误信息
      throw new Error(raw.message || '后端返回错误');
    }
    // code=0 或 200 → 成功，从 data 字段提取
    const payload = 'data' in raw ? raw.data : raw;
    const taskId = String(payload?.jobId ?? payload?.id ?? '');
    if (!taskId || taskId === 'undefined') {
      throw new Error('后端未返回有效的 jobId，响应: ' + JSON.stringify(raw));
    }
    return { taskId };
  }
  // 非包装格式，直接返回业务对象
  const taskId = String(raw?.jobId ?? raw?.id ?? '');
  if (!taskId || taskId === 'undefined') {
    throw new Error('后端未返回有效的 jobId，响应: ' + JSON.stringify(raw));
  }
  return { taskId };
}

export async function getTask(id: string): Promise<VideoTask> {
  try {
    const job = await request<any>(`/api/jobs/${id}`);
    console.debug('[getTask] job received:', job);
    return mapJobToTask(job);
  } catch (error) {
    console.warn('[getTask] failed for id=' + id, error);
    // 返回一个占位任务，保留原有的进度显示，不强制标 FAILED
    return {
      id,
      status: 'queued',
      progress: 0,
      request: {} as CreateVideoRequest,
      error: error instanceof Error ? error.message : String(error),
      estimatedCredits: 0,
      createdAt: Date.now(),
      updatedAt: Date.now(),
    };
  }
}

export async function listTasks(q: ListTasksQuery = {}): Promise<ListTasksResponse> {
  try {
    return await request<ListTasksResponse>(API, { query: q as Record<string, string | number> });
  } catch {
    // 后端 /api/videos 尚未实现，返回空列表不阻塞页面
    return { items: [], total: 0 };
  }
}

export async function cancel(id: string): Promise<void> {
  await request<void>(`${API}/${id}/cancel`, { method: 'POST' });
}

export async function retry(id: string): Promise<CreateVideoResponse> {
  return request<CreateVideoResponse>(`${API}/${id}/retry`, { method: 'POST' });
}
