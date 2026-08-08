// 图片生成 API —— 真实后端调用
import * as real from './image.real';

export type { ImageGenerateParams, ImageGenerateResult, FavoriteImageResult } from './image.real';

export const imageApi = {
  /**
   * AI 图片生成
   * @param params 生成参数（prompt, size, quality, style, referenceImages）
   * @param signal 取消信号（用于超时控制）
   * @returns 生成的图片信息
   */
  generateImage: (
    params: import('./image.real').ImageGenerateParams,
    signal?: AbortSignal
  ): Promise<import('./image.real').ImageGenerateResult> =>
    real.generateImage(params, signal),

  /**
   * 收藏图片（上传到 MinIO）
   * @param imageData base64 data URI 格式的图片数据
   */
  favoriteImage: (imageData: string) => real.favoriteImage(imageData),

  /**
   * 获取用户收藏图片列表
   */
  getFavorites: () => real.getFavorites(),

  /**
   * 取消收藏（从 MinIO 删除）
   * @param objectKey 图片在 MinIO 中的 objectKey
   */
  unfavoriteImage: (objectKey: string) => real.unfavoriteImage(objectKey),
};
