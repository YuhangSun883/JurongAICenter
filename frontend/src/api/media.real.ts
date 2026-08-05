// 真实后端实现 —— 文件上传走 multipart/form-data，其它用 JSON
import { request } from '@/lib/http';
import type {
  MediaItem,
  MediaListQuery,
  MediaUploadResponse,
  RoleCategory,
  RoleListQuery,
} from '@/types/media';

const API = '/api/media';

export async function listAssets(q: MediaListQuery = {}): Promise<{ items: MediaItem[]; total: number }> {
  return request(`${API}/assets`, { query: q as Record<string, string | number> });
}

export async function deleteAsset(id: string): Promise<void> {
  await request(`${API}/assets/${id}`, { method: 'DELETE' });
}

/** 文件上传：后端期望 multipart/form-data，单文件 */
export async function uploadAsset(file: File): Promise<MediaUploadResponse> {
  const form = new FormData();
  form.append('file', file);
  const token = typeof window !== 'undefined' ? localStorage.getItem('token') : null;
  const res = await fetch(`${API}/assets`, {
    method: 'POST',
    headers: token ? { Authorization: `Bearer ${token}` } : undefined,
    body: form,
  });
  if (!res.ok) throw new Error(`upload failed: ${res.status}`);
  return res.json();
}

/** 角色库分类 */
export async function listRoleCategories(): Promise<RoleCategory[]> {
  return request<RoleCategory[]>(`${API}/roles/categories`);
}

/** 角色库列表 */
export async function listRoles(q: RoleListQuery = {}): Promise<{ items: MediaItem[]; total: number }> {
  return request(`${API}/roles`, { query: q as Record<string, string | number> });
}
