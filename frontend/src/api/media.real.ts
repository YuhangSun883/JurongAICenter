// 媒体资产 API（真实实现）

import { request } from '@/lib/http';
import type {
  MediaLibrary,
  MediaAsset,
  MediaAssetListResponse,
  UploadMediaResponse,
  MediaRoleCategory,
  MediaRole,
} from '@/types/media';

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