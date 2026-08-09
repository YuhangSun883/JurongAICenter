import { request } from '@/lib/http';
import { getAccessToken } from '@/lib/auth-store';
import type {
  CanvasDetail,
  CanvasListItem,
  CanvasNode,
  CreateCanvasNodeRequest,
  CreateCanvasRequest,
  GenerateCanvasNodeRequest,
  GenerateCanvasNodeResponse,
  UpdateCanvasNodeRequest,
  UploadToCanvasOptions,
} from './canvas';

const API = '/api/canvas';

export function createNode(req: CreateCanvasNodeRequest): Promise<CanvasNode> {
  return request<CanvasNode>(`${API}/nodes`, { method: 'POST', body: req });
}

export function updateNode(req: UpdateCanvasNodeRequest): Promise<CanvasNode> {
  return request<CanvasNode>(`${API}/nodes/${req.nodeId}`, {
    method: 'PATCH',
    body: req,
  });
}

export function generateNode(req: GenerateCanvasNodeRequest): Promise<GenerateCanvasNodeResponse> {
  return request<GenerateCanvasNodeResponse>(`${API}/nodes/${req.nodeId}/generate`, {
    method: 'POST',
    body: req,
  });
}

/** 轮询任务状态 */
export function getTask(taskId: string): Promise<GenerateCanvasNodeResponse> {
  return request<GenerateCanvasNodeResponse>(`${API}/tasks/${taskId}`);
}

/** 创建一个新画布(返回 CanvasListItem 含新 canvas id) */
export function createCanvas(req: CreateCanvasRequest): Promise<CanvasListItem> {
  return request<CanvasListItem>(`${API}/canvases`, { method: 'POST', body: req });
}

/** 我的画布列表("我的创作"侧边面板用) */
export function listCanvases(page = 1, pageSize = 50): Promise<CanvasListItem[]> {
  return request<CanvasListItem[]>(`${API}/canvases?page=${page}&pageSize=${pageSize}`);
}

/** 拿画布完整快照（画布 + 所有节点 + 所有连线） */
export function getCanvasDetail(canvasId: string): Promise<CanvasDetail> {
  return request<CanvasDetail>(`${API}/canvases/${canvasId}`);
}

/** 拿单个节点 */
export function getNode(nodeId: string): Promise<CanvasNode> {
  return request<CanvasNode>(`${API}/nodes/${nodeId}`);
}

/** 删除画布（后端级联删节点 + 任务） */
export function deleteCanvas(canvasId: string): Promise<{ canvasId: string; status: string }> {
  return request<{ canvasId: string; status: string }>(`${API}/canvases/${canvasId}`, {
    method: 'DELETE',
  });
}

/**
 * 本地上传文件到画布：自动建对应类型的画布节点。
 *
 * <p>后端按 mime/扩展名自动判断节点类型：
 * <ul>
 *   <li>image/* → image 节点</li>
 *   <li>video/* → video 节点</li>
 *   <li>audio/* → audio 节点</li>
 * </ul>
 *
 * <p>用裸 fetch 处理 multipart（不走 request()，因为它会强制 Content-Type: application/json）。
 *
 * @param file   文件（图片 / 视频 / 音频）
 * @param opts   canvasId / title / positionX / positionY（可空）
 */
export async function uploadToCanvas(
  file: File,
  opts: UploadToCanvasOptions = {},
): Promise<CanvasNode> {
  const fd = new FormData();
  fd.append('file', file);
  if (opts.canvasId) fd.append('canvasId', opts.canvasId);
  if (opts.title) fd.append('title', opts.title);
  if (opts.positionX != null) fd.append('positionX', String(opts.positionX));
  if (opts.positionY != null) fd.append('positionY', String(opts.positionY));

  const API_BASE = process.env.NEXT_PUBLIC_API_BASE_URL ?? 'http://localhost:4000';
  const token = getAccessToken();
  const headers: Record<string, string> = {};
  if (token) headers.Authorization = `Bearer ${token}`;

  const res = await fetch(`${API_BASE}${API}/upload`, {
    method: 'POST',
    body: fd,
    headers,
    // 不要设 Content-Type，让浏览器自动加 multipart boundary
  });

  // 保护：空 body / HTML 错误页不能让 res.json() 抱 raw “Unexpected end of JSON input”
  let json: unknown = null;
  try {
    json = await res.json();
  } catch {
    /* ignore — body 不是 JSON */
  }

  if (!res.ok) {
    const body = json as { message?: string } | null;
    const msg = body?.message || `upload failed: HTTP ${res.status}`;
    throw new Error(msg);
  }

  // 后端标准包装 {code, data, message}
  if (json && typeof json === 'object' && 'data' in (json as object)) {
    return (json as { data: CanvasNode }).data;
  }
  return json as CanvasNode;
}