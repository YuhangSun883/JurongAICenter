// 媒体资产 API（真实实现）

import { request } from '@/lib/http';
import type {
  BatchDeleteRequest,
  CreateLibraryRequest,
  MediaItem,
  MediaLibrary,
  MediaListQuery,
  MediaUploadResponse,
  PatchAssetRequest,
  RoleCategory,
  RoleListQuery,
} from '@/types/media';
import type { PageResult } from '@/types/api';

/** 资产库列表 */
export async function listLibraries(): Promise<MediaLibrary[]> {
  const res = await request<MediaLibrary[]>('/api/media/libraries');
  return res ?? [];
}

/**
 * 素材列表（带筛选 + 分页）
 * @param libraryId null = 全部库
 * @param type      image / video / audio, null = 全部
 * @param source    uploaded / ai-generated, null = 全部
 * @param keyword   文件名模糊匹配
 */
export async function listAssets(params: {
  libraryId?: number | null;
  type?: string | null;
  source?: string | null;
  keyword?: string;
  page?: number;
  pageSize?: number;
} = {}): Promise<MediaAssetListResponse> {
  const query: Record<string, string | number | null> = {};
  if (params.libraryId != null) query.libraryId = params.libraryId;
  if (params.type) query.type = params.type;
  if (params.source) query.source = params.source;
  if (params.keyword) query.keyword = params.keyword;
  query.page = params.page ?? 1;
  query.pageSize = params.pageSize ?? 24;

  const res = await request<MediaAssetListResponse>('/api/media/assets', { query });
  return res ?? { items: [], total: 0, page: 1, pageSize: 24 };
}

/** 单条素材 */
export async function getAsset(id: number): Promise<MediaAsset> {
  const res = await request<MediaAsset>(`/api/media/assets/${id}`);
  return res as MediaAsset;
}

/**
 * 上传文件
 * @param file      File 对象（来自 input[type=file] 或拖拽）
 * @param libraryId 选填，不传则上传到"我的资产"系统默认库
 * @returns 上传成功的素材信息（含 url）
 */
export async function upload(file: File, libraryId?: number): Promise<UploadMediaResponse> {
  const fd = new FormData();
  fd.append('file', file);
  if (libraryId != null) {
    fd.append('libraryId', String(libraryId));
  }
  // 注意:request() 默认发 JSON；这里手动 fetch 处理 multipart
  const token = typeof window !== 'undefined' ? localStorage.getItem('access_token') : null;
  const headers: Record<string, string> = {};
  if (token) headers.Authorization = `Bearer ${token}`;

  const API_BASE = process.env.NEXT_PUBLIC_API_BASE_URL ?? 'http://localhost:4000';
  const r = await fetch(`${API_BASE}/api/media/upload`, {
// ============ 资产库 ============

/** 列出我的资产库（含 2 个系统库 + custom 库） */
export async function listLibraries(): Promise<MediaLibrary[]> {
  return request<MediaLibrary[]>(`${API}/libraries`);
}

/** 新建 custom 库 */
export async function createLibrary(req: CreateLibraryRequest): Promise<MediaLibrary> {
  return request<MediaLibrary>(`${API}/libraries`, { method: 'POST', body: req });
}

/** 重命名库 */
export async function renameLibrary(id: number, req: CreateLibraryRequest): Promise<MediaLibrary> {
  return request<MediaLibrary>(`${API}/libraries/${id}`, { method: 'PATCH', body: req });
}

/** 删除库（custom 库可删，连素材一起删） */
export async function deleteLibrary(id: number): Promise<void> {
  await request(`${API}/libraries/${id}`, { method: 'DELETE' });
}

// ============ 素材 ============

/** 分页查询素材 */
export async function listAssets(q: MediaListQuery = {}): Promise<PageResult<MediaItem>> {
  return request(`${API}/assets`, { query: q as Record<string, string | number> });
}

/** 素材详情 */
export async function getAsset(id: number): Promise<MediaItem> {
  return request<MediaItem>(`${API}/assets/${id}`);
}

/** 改名 */
export async function renameAsset(id: number, name: string): Promise<MediaItem> {
  return request<MediaItem>(`${API}/assets/${id}`, {
    method: 'PATCH',
    body: { name } as PatchAssetRequest,
  });
}

/** 单删 */
export async function deleteAsset(id: number): Promise<void> {
  await request(`${API}/assets/${id}`, { method: 'DELETE' });
}

/** 批量删除 */
export async function batchDeleteAssets(ids: number[]): Promise<{ deleted: number; requested: number }> {
  return request(`${API}/assets/batch-delete`, {
    method: 'POST',
    body: { ids } as BatchDeleteRequest,
  });
}

/** 文件上传：后端期望 multipart/form-data，libraryId 可选 */
export async function uploadAsset(file: File, libraryId?: number): Promise<MediaUploadResponse> {
  const form = new FormData();
  form.append('file', file);
  if (libraryId != null) {
    form.append('libraryId', String(libraryId));
  }
  const token = typeof window !== 'undefined' ? localStorage.getItem('token') : null;
  const res = await fetch(`${API}/assets`, {
    method: 'POST',
    body: fd,
    headers,
    // 不要设 Content-Type,让浏览器自动加 multipart boundary
  });
  const json = await r.json();
  if (json && typeof json === 'object' && 'code' in json && 'data' in json) {
    if (json.code !== 0) {
      throw new Error(json.message || `HTTP ${json.code}`);
    }
    return json.data as UploadMediaResponse;
  }
  if (!r.ok) throw new Error(`HTTP ${r.status}`);
  return json as UploadMediaResponse;
}

/** 删除素材 */
export async function deleteAsset(id: number): Promise<void> {
  await request<void>(`/api/media/assets/${id}`, { method: 'DELETE' });
  if (!res.ok) {
    // 尝试解析后端返回的错误信息
    let msg = `upload failed: ${res.status}`;
    try {
      const body = await res.json();
      if (body?.message) msg = body.message;
    } catch {
      // ignore
    }
    throw new Error(msg);
  }
  return res.json();
}

// ============ 角色库（兼容旧接口） ============

/** 角色库分类 */
export async function listRoleCategories(): Promise<RoleCategory[]> {
  return request<RoleCategory[]>(`${API}/roles/categories`);
}

/** 角色分类 */
export async function listRoleCategories(): Promise<MediaRoleCategory[]> {
  const res = await request<MediaRoleCategory[]>('/api/media/roles/categories');
  return res ?? [];
}

/** 角色列表 */
export async function listRoles(category?: string): Promise<MediaRole[]> {
  const query: Record<string, string> = {};
  if (category) query.category = category;
  const res = await request<MediaRole[]>('/api/media/roles', { query });
  return res ?? [];
}