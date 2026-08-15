export type MediaType = 'image' | 'video' | 'audio';
export type MediaSource = 'uploaded' | 'ai-generated';
export type LibraryType = 'system-uploaded' | 'system-ai' | 'custom';
/** 资产库业务类型（V18 引入） */
export type LibraryBizType = 'normal' | 'virtual_human' | 'real_person';
/** 真人库授权状态（后端按 authExpireAt 计算） */
export type AuthStatus = 'valid' | 'expired' | 'none';

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
  // ============== V18 字段 ==============
  /** 业务类型：normal / virtual_human / real_person */
  bizType?: LibraryBizType | string;
  /** 授权用途说明（仅 real_person） */
  authPurpose?: string;
  /** 授权有效期（仅 real_person），格式 yyyy-MM-dd */
  authExpireAt?: string;
  /** 授权状态：valid / expired / none */
  authStatus?: AuthStatus | string;
  // ============== V19 父子库字段 ==============
  /** 父库 id，null=根库 */
  parentId?: number | null;
  /** 子库列表（仅 DTO 透传用，不持久化） */
  children?: MediaLibrary[];
  /** 是否存在子库（前端用于显示"打开子库"按钮） */
  hasChildren?: boolean;
  /** 树深度（0=根），用于缩进展示 */
  depth?: number;
}

export interface MediaItem {
  id: number;
  libraryId?: number | null;
  libraryName?: string;
  type: MediaType;
  source: MediaSource;
  url: string;
  /** MinIO 对象 key，前端用于按内容去重（url 预签名每次不同，不稳定） */
  objectKey?: string;
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

export type MediaAsset = MediaItem;

export interface MediaListQuery {
  libraryId?: number | null;
  type?: MediaType | string;
  source?: MediaSource | 'all' | string;
  keyword?: string;
  page?: number;
  pageSize?: number;
}

export interface MediaUploadResponse {
  id: number;
  url: string;
  name: string;
  type: MediaType;
  size: number;
  sizeBytes?: number;
}

export interface BatchDeleteRequest {
  ids: number[];
}

export interface PatchAssetRequest {
  name?: string;
  /**
   * 2026-08-15：可选。改素材所属库（move 操作）。
   * - 不传 / null：表示不动库
   * - 传具体 id：把素材搬到目标库（后端会校验归属和不能移到 system-ai）
   */
  libraryId?: number;
}

export interface CreateLibraryRequest {
  name: string;
  iconKey?: string;
  description?: string;
  // ============== V18 字段 ==============
  /** 业务类型：normal / virtual_human / real_person，默认 normal */
  bizType?: LibraryBizType;
  /** 授权用途说明（仅 real_person） */
  authPurpose?: string;
  /** 授权有效期（仅 real_person），格式 yyyy-MM-dd */
  authExpireAt?: string;
  // ============== V19 父子库字段 ==============
  /**
   * 父库 id，可选：
   * - null/不传：建根库
   * - 传具体 id：建为该父库的子库
   * 后端会校验父库存在、归属、非系统库、类型匹配
   */
  parentId?: number | null;
}

export interface RoleCategory {
  key: string;
  label: string;
}

export type MediaRoleCategory = RoleCategory;

export interface MediaRole {
  id: number | string;
  name: string;
  category: string;
  imageUrl: string;
  description?: string;
  tags?: string[];
  createdAt?: number;
}

export interface RoleListQuery {
  category?: string;
  keyword?: string;
  page?: number;
  pageSize?: number;
}