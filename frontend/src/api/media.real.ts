// 真实后端实现 —— 文件上传走 multipart/form-data，其它用 JSON
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

// ============ 角色库（兼容旧接口） ============

/** 角色库分类 */
export async function listRoleCategories(): Promise<RoleCategory[]> {
  return request<RoleCategory[]>(`${API}/roles/categories`);
}

/** 角色库列表 */
export async function listRoles(q: RoleListQuery = {}): Promise<{ items: MediaItem[]; total: number }> {
  return request(`${API}/roles`, { query: q as Record<string, string | number> });
}
