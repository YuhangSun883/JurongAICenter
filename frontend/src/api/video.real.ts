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
  TaskStatus,
  VideoTask,
} from '@/types/video';

// 注意:本文件服务于 ai-video 工作台(/ai-video 页面),与画布(/canvas)视频生成无关。
// 画布视频生成走 canvas.real.ts → /api/canvas/nodes/{id}/generate-video。
// ai-video 独立工具项目前不在本次修复范围,保留原样。
const API = '/api/videos';
const JOBS_API = '/api/jobs';

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

/**
 * 把后端返回的 MinIO 签名 URL（或 asset:// 等内部 scheme）转换成同域后端代理 URL。
 * 原因：浏览器对跨域 MinIO URL 会触发 ORB / CORS 拒绝，导致视频无法播放。
 * 解决方案：改用 /api/media/assets/{id}/stream（同域，无 ORB 问题）。
 *
 * @param jobId  后端 job/asset id
 * @param rawUrl 后端 resultUrls 里的原始 URL（可能是 MinIO 签名 URL）
 */
function toBackendStreamUrl(jobId: string | number, rawUrl?: string): string | undefined {
  if (!jobId) return rawUrl;
  const token = getAccessToken();
  const qs = token ? `?token=${encodeURIComponent(token)}` : '';
  return `/api/media/assets/${jobId}/stream${qs}`;
}

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
  let rawResultUrl: string | undefined;
  if (Array.isArray(job.resultUrls) && job.resultUrls.length > 0) {
    rawResultUrl = job.resultUrls[0];
  } else if (typeof job.resultUrls === 'string' && job.resultUrls) {
    try {
      const arr = JSON.parse(job.resultUrls);
      if (Array.isArray(arr) && arr.length > 0) rawResultUrl = arr[0];
    } catch { /* ignore */ }
  }

  // 把 MinIO 签名 URL 转换成后端代理 URL，避免浏览器跨域 ORB/CORS 拦截
  // MinIO URL 形如 http://host:port/bucket-name/object-key?X-Amz-...
  // 后端代理 URL 形如 /api/media/assets/{mediaAssetId}/stream?token=xxx
  // 注意：job.id 是任务 id，跟 media_asset.id 不一样；必须用 mediaAssetId 才能 stream
  const streamId = job.mediaAssetId ?? job.id;
  const resultUrl = streamId != null ? toBackendStreamUrl(streamId, rawResultUrl) : rawResultUrl;

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
  // 后端：POST /api/videos/text-to-video（VideoGenerationController#textToVideo）
  // 前端将 CreateVideoRequest 映射到该接口的请求体字段
  const backendBody = {
    script: req.script,
    model: req.model,
    aspectRatio: req.aspectRatio,
    resolution: req.resolution,
    duration: req.duration,
    audioMode: req.audioMode ?? 'mute',
  };
  const resp = await request<any>(`${API}/text-to-video`, { method: 'POST', body: backendBody });
  // 后端返回 GenerateResponse { jobId, status, ... } 或被包装 {code, data}
  const taskId = String(resp?.jobId ?? resp?.id ?? '');
  if (!taskId || taskId === 'undefined') {
    throw new Error('后端未返回有效的 jobId，响应: ' + JSON.stringify(resp));
  }
  return { taskId, estimatedCredits: 0 };
}

/**
 * 图生视频 — 调用后端 POST /api/videos/image-to-video (multipart)
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

  // 发送到后端（走相对路径，由 Next.js rewrites 代理到 :8080）
  const token = getAccessToken();
  const resp = await fetch(`${API}/image-to-video`, {
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
    return { taskId, estimatedCredits: 0 };
  }
  // 非包装格式，直接返回业务对象
  const taskId = String(raw?.jobId ?? raw?.id ?? '');
  if (!taskId || taskId === 'undefined') {
    throw new Error('后端未返回有效的 jobId，响应: ' + JSON.stringify(raw));
  }
  return { taskId, estimatedCredits: 0 };
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
  try {
    const resp = await request<{ items: any[]; total: number }>(
      API,
      { query: q as Record<string, string | number> }
    );
    return {
      items: (resp.items || []).map(mapJobToTask),
      total: resp.total || 0,
    };
  } catch {
    return { items: [], total: 0 };
  }
}

export async function cancel(id: string): Promise<void> {
  await request<void>(`${API}/${id}/cancel`, { method: 'POST' });
}

export async function retry(id: string): Promise<CreateVideoResponse> {
  return request<CreateVideoResponse>(`${API}/${id}/retry`, { method: 'POST' });
}
