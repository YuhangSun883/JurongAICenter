// 图片生成 API —— 真实后端调用
import { request, ApiError } from '@/lib/http';
import { getAccessToken } from '@/lib/auth-store';
import { uploadAicomingAsset } from './media.real';

const API = '/api/images';

/** 图片生成请求参数 */
export interface ImageGenerateParams {
  prompt: string;
  size?: string;       // 图片尺寸，默认 1024x1024
  quality?: string;    // 图片质量，默认 standard
  style?: string;      // 图片风格，默认 vivid
  /** 已上传到 MinIO 的图片 URL（按文档 §2 用 image 字段） */
  imageUrls?: string[];
  /**
   * 引用图片 base64 data URI 列表（兼容旧版路径，multipart /v1/images/edits）。
   * 与 imageUrls 二选一：如果两个都给了，优先 imageUrls。
   */
  referenceImages?: string[];
}

/** 图片生成响应 */
export interface ImageGenerateResult {
  imageUrl: string;    // 图片地址（base64 data URI 或 MinIO URL）
  model: string;       // 使用的模型名称
  originalUrl: string; // NewAPI 原始返回的 URL
  /** MinIO 对象 key（用于和后端历史列表去重 + 收藏） */
  objectKey?: string;
  /** MinIO 可访问 URL（用于和后端历史列表去重 + 直接展示） */
  assetUrl?: string;
}

/** 收藏图片响应 */
export interface FavoriteImageResult {
  objectKey: string;   // MinIO objectKey（用于取消收藏时传给后端）
  url: string;         // MinIO 可访问 URL
  createdAt: number;   // 收藏时间戳
}

/**
 * 调用后端 AI 图片生成接口
 * 三种路径：
 *   1. 有 imageUrls（推荐，对齐文档 §2）：发 JSON body，image=URL
 *   2. 有 referenceImages（base64 兼容旧版）：发 JSON body，后端转 multipart
 *   3. 都没有：纯 T2I，JSON body
 *
 * 前置步骤：imageUrls 里的 blob: URL 会被自动上传到聚融素材库（按 v2.1 文档 §9），
 * 转为 asset://aic_xxx 后再传给后端（绕过 PrivacyInformation 审核）。
 */
export async function generateImage(
  params: ImageGenerateParams,
  signal?: AbortSignal
): Promise<ImageGenerateResult> {
  // 前置：把 blob: URL 上传到 aicoming 拿 asset_url
  let finalImageUrls = params.imageUrls;
  if (params.imageUrls && params.imageUrls.length > 0) {
    const resolved: string[] = [];
    for (const url of params.imageUrls) {
      if (url && url.startsWith('blob:')) {
        try {
          const blob = await (await fetch(url)).blob();
          // 从 blob URL 推测扩展名
          const ext = blob.type.includes('jpeg') || blob.type.includes('jpg')
            ? 'jpg' : 'png';
          const file = new File([blob], `ref_${Date.now()}.${ext}`, { type: blob.type || 'image/png' });
          console.log('[generateImage] 上传 blob 到 aicoming 素材库...');
          const asset = await uploadAicomingAsset(file);
          if (asset && asset.asset_url) {
            console.log('[generateImage] 拿到 asset_url:', asset.asset_url);
            resolved.push(asset.asset_url);
            continue;
          }
          throw new Error('素材上传响应缺少 asset_url');
        } catch (err) {
          throw new ApiError(0,
            `引用图片上传到素材库失败: ${err instanceof Error ? err.message : String(err)}`);
        }
      }
      resolved.push(url);
    }
    finalImageUrls = resolved;
  }

  return request<ImageGenerateResult>(`${API}/generate`, {
    method: 'POST',
    body: { ...params, imageUrls: finalImageUrls },
    signal,
  });
}

/**
 * 收藏图片（修改 source_tool 字段，不复制图片）
 * @param objectKey 已生成图片在 MinIO 中的 objectKey
 */
export async function favoriteImage(
  objectKey: string
): Promise<FavoriteImageResult> {
  return request<FavoriteImageResult>(`${API}/favorite`, {
    method: 'POST',
    body: { objectKey },
  });
}

/**
 * 获取用户收藏图片列表
 */
export async function getFavorites(): Promise<FavoriteImageResult[]> {
  return request<FavoriteImageResult[]>(`${API}/favorites`, {
    method: 'GET',
  });
}

/**
 * 取消收藏（从 MinIO 删除）
 * @param objectKey 图片在 MinIO 中的 objectKey
 */
export async function unfavoriteImage(
  objectKey: string
): Promise<{ success: boolean }> {
  return request<{ success: boolean }>(`${API}/favorite?objectKey=${encodeURIComponent(objectKey)}`, {
    method: 'DELETE',
  });
}