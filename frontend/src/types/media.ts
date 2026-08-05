// 媒体（素材库 / 角色库 / 上传）

export type MediaType = 'image' | 'video' | 'audio';
export type MediaSource = 'uploaded' | 'ai-generated';
export type MediaCategory = 'image' | 'video' | 'audio';

export interface MediaItem {
  id: string;
  type: MediaType;
  source: MediaSource;
  url: string;
  name: string;
  size?: number;
  width?: number;
  height?: number;
  createdAt: number;
}

/** 列表查询 */
export interface MediaListQuery {
  category?: MediaCategory;
  source?: MediaSource | 'all';
  keyword?: string;
  page?: number;
  pageSize?: number;
}

/** 上传请求 */
export interface MediaUploadRequest {
  file: File;
  type: MediaType;
  /** 角色库分类（仅当 category=role 时使用） */
  roleCategory?: string;
}

/** 上传响应 */
export interface MediaUploadResponse {
  item: MediaItem;
}

/** 角色分类 */
export interface RoleCategory {
  key: string;
  label: string;
}

/** 角色库列表查询 */
export interface RoleListQuery {
  category?: string;
  keyword?: string;
  page?: number;
  pageSize?: number;
}
