// 媒体资产类型定义（前后端共用契约）
// 媒体（素材库 / 资产库 / 上传 / 角色库）

export type MediaType = 'image' | 'video' | 'audio';
export type MediaSource = 'uploaded' | 'ai-generated';

/** system-uploaded / system-ai / custom */
export type LibraryType = 'system-uploaded' | 'system-ai' | 'custom';

/** 资产库（与后端 MediaLibraryResponse 字段对齐） */
export interface MediaLibrary {
  id: number;
  name: string;
  type: LibraryType | string;
  iconKey?: string;
  description?: string;
  sortOrder?: number;
  assetCount?: number;
  createdAt?: string | number;
  updatedAt?: string | number;
}

/** 素材（与后端 MediaAssetResponse 字段对齐） */
export interface MediaItem {
  id: number;
  libraryId?: number | null;
  libraryName?: string;
  type: MediaType;
  source: MediaSource;
  url: string;
  name: string;
  mimeType?: string;
  /** 后端字段 sizeBytes，前端可兼容 size */
  size?: number;
  sizeBytes?: number;
  width?: number;
  height?: number;
  /** 后端字段 durationSec，前端可兼容 duration */
  duration?: number;
  durationSec?: number;
  sourceTool?: string;
  sourceTaskId?: string;
  createdAt: string;
  updatedAt?: string;
}

/** 列表查询 */
export interface MediaListQuery {
  libraryId?: number;
  type?: MediaType;
  source?: MediaSource | 'all';
  keyword?: string;
  page?: number;
  pageSize?: number;
}

/** 上传响应（与后端 MediaUploadResponse 字段对齐） */
export interface MediaUploadResponse {
  id: number;
  url: string;
  name: string;
  type: MediaType;
  size: number;
}

/** 批量删除请求 */
export interface BatchDeleteRequest {
  ids: number[];
}

/** 改名请求 */
export interface PatchAssetRequest {
  name: string;
}

/** 创建库请求 */
export interface CreateLibraryRequest {
  name: string;
  iconKey?: string;
  description?: string;
}

/** 角色库分类 */
export interface RoleCategory {
  key: string;
  label: string;
}

/** 角色库实体 */
export interface MediaRole {
  id: number;
  name: string;
  category: string;
  imageUrl: string;
  description?: string;
  tags?: string[];
  createdAt?: number;
}

/** 角色库列表查询 */
export interface RoleListQuery {
  category?: string;
  keyword?: string;
  page?: number;
  pageSize?: number;
}