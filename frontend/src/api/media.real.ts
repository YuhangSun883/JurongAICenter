// 媒体资产 API（真实实现）
//
// 后端路由前缀统一为 /api/media（见 MediaController.java）
// - 资产库:    /api/media/libraries + /api/media/libraries/{id}
// - 素材:      /api/media/assets + /api/media/assets/{id}
// - 角色库:    /api/media/roles/categories + /api/media/roles

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
} from '@/types/media';
import type { PageResult } from '@/types/api';

const API = '/api/media';

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
  return request<PageResult<MediaItem>>(`${API}/assets`, { query: q as Record<string, string | number> });
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

/**
 * 文件上传：后端期望 multipart/form-data，libraryId 可选。
 * 注意：不能用 @/lib/http 的 request()（它会强制 JSON Content-Type），
 * 这里手动 fetch 让浏览器自动加 multipart boundary。
 */
export async function uploadAsset(file: File, libraryId?: number): Promise<MediaUploadResponse> {
  const form = new FormData();
  form.append('file', file);
  if (libraryId != null) {
    form.append('libraryId', String(libraryId));
  }
  const token = typeof window !== 'undefined' ? localStorage.getItem('token') : null;
  const API_BASE = process.env.NEXT_PUBLIC_API_BASE_URL ?? 'http://localhost:4000';
  const res = await fetch(`${API_BASE}${API}/assets`, {
    method: 'POST',
    headers: token ? { Authorization: `Bearer ${token}` } : undefined,
    body: form,
  });
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

// ============ 角色库（同事保留，给画布/Agent 用） ============

/** 角色库分类 */
export async function listRoleCategories(): Promise<RoleCategory[]> {
  return request<RoleCategory[]>(`${API}/roles/categories`);
}

/** 角色列表（可选按分类筛选） */
export async function listRoles(category?: string): Promise<unknown[]> {
  const query: Record<string, string> = {};
  if (category) query.category = category;
  return request(`${API}/roles`, { query });
}