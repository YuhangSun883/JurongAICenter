// 视频生成 - 真实后端实现
import { request, ApiError } from '@/lib/http';
import { getAccessToken } from '@/lib/auth-store';
import { uploadAicomingAsset } from './media.real';
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
  // 原始 TOS / MinIO 签名 URL（按 API 文档 §7，24h 有效）—— 给下载/分享/调试用，
  // 不能直接喂 <video>（会被 ORB 拦截）。
  const videoUrl = rawResultUrl;

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
    videoUrl,
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
  // 请求体按《聚融中转 API 接口文档》§3 文生视频 + §6 视频字段说明对齐：
  //   - 顶层 model / prompt
  //   - metadata.duration / metadata.resolution / metadata.ratio / metadata.watermark
  //     / metadata.generate_audio / metadata.return_last_frame（文档约定）
  // 同时保留后端 controller 识别的顶层字段（script/aspectRatio/audioMode）做向后兼容。
  const backendBody: Record<string, unknown> = {
    // 后端 controller 期望的字段（当前实现读这些）
    script: req.script,
    model: req.model,
    aspectRatio: req.aspectRatio,
    resolution: req.resolution,
    duration: req.duration,
    audioMode: req.audioMode ?? 'mute',
    // 文档 §3/§6：把 prompt / model 顶层冗余声明，方便后端后续按文档实现
    prompt: req.script,
    // 文档 §6：metadata 嵌套（后端 controller 当前不读，但传了不影响运行；后续若按文档改造直接用）
    metadata: {
      duration: req.duration,
      resolution: req.resolution,
      ratio: req.aspectRatio,
      watermark: false,
      generate_audio: req.audioMode !== 'mute',
      return_last_frame: true,
    },
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
 *
 * 请求字段（按《聚融中转站接口手册 v2.1》§7 + 后端 controller）：
 *   - imageUrl（表单字段）：http(s):// 或 asset://aic_xxx
 *     这是文档推荐的"URL 转发"模式，绕过 ORB/CORS + 支持 asset:// 引用。
 *   - file（multipart，可选）：兼容旧版 multipart 直传
 *   - prompt / duration / resolution / metadata
 *
 * 前置：blob: URL 会被自动上传到聚融素材库（v2.1 文档 §9）拿 asset_url，
 *        解决"前端 blob URL 没真正进 NewAPI 请求"的问题。
 */
export async function createImageToVideo(
  imageUrl: string,
  prompt: string,
  duration: number,
  resolution: string
): Promise<CreateVideoResponse> {
  // 1. 预处理：blob: URL → 上传到 aicoming 拿 asset://aic_xxx
  let finalImageUrl = imageUrl;
  if (imageUrl && imageUrl.startsWith('blob:')) {
    try {
      const blob = await (await fetch(imageUrl)).blob();
      const ext = blob.type.includes('jpeg') || blob.type.includes('jpg')
        ? 'jpg' : 'png';
      const file = new File([blob], `ref_${Date.now()}.${ext}`, { type: blob.type || 'image/png' });
      console.log('[createImageToVideo] 上传 blob 到 aicoming 素材库...');
      const asset = await uploadAicomingAsset(file);
      if (asset && asset.asset_url) {
        console.log('[createImageToVideo] 拿到 asset_url:', asset.asset_url);
        finalImageUrl = asset.asset_url;
      } else {
        throw new Error('素材上传响应缺少 asset_url');
      }
    } catch (e) {
      throw new ApiError(0,
        `引用图片上传到素材库失败: ${e instanceof Error ? e.message : String(e)}`);
    }
  }

  const formData = new FormData();
  // 优先用 URL 转发模式（按文档 §7）
  // 判断：URL 必须是 http(s):// 或 asset:// 开头
  const useUrlMode =
    finalImageUrl &&
    (finalImageUrl.startsWith('http://') ||
      finalImageUrl.startsWith('https://') ||
      finalImageUrl.startsWith('asset://'));

  if (useUrlMode) {
    // 模式 A：URL 转发（推荐）
    formData.append('imageUrl', finalImageUrl);
    console.debug('[createImageToVideo] 使用 URL 转发模式:', finalImageUrl);
  } else {
    // 模式 B：下载图片为 Blob 后 multipart 上传（兼容旧路径）
    // 注意：跨域 MinIO URL 可能被 ORB 拦截 → blob 为空 → 后端会报"file 不能为空"
    console.warn('[createImageToVideo] URL 非 http(s)/asset，回退为 multipart 上传:', finalImageUrl);
    try {
      const blob = await (await fetch(finalImageUrl)).blob();
      const file = new File([blob], 'reference.jpg', { type: blob.type || 'image/jpeg' });
      formData.append('file', file);
    } catch (e) {
      throw new ApiError(0,
        `无法加载参考图片（URL=${finalImageUrl}）：${e instanceof Error ? e.message : String(e)}`);
    }
  }

  formData.append('prompt', prompt);
  formData.append('duration', String(duration));
  formData.append('resolution', resolution);
  // 文档 §6 metadata 嵌套：后端 controller 当前不读，传了不破坏现有逻辑
  formData.append(
    'metadata',
    JSON.stringify({
      duration,
      resolution,
      watermark: false,
      generate_audio: false,
      return_last_frame: true,
    })
  );

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
