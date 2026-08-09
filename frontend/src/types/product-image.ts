/** 商品套图（商详）类型定义 —— 前端约定，后端按此实现 */

/** 语言选项 */
export type ProductImageLang = '中文' | '英文' | '日文' | '韩文';

/** 张数选项 */
export type ProductImageCount = '4 张' | '6 张' | '9 张';

/** 模型档位（高级版 / 标准版 / ...） */
export interface ProductImageModel {
  key: string;
  label: string;
  /** 描述：高级版 VIP · 更快更稳 */
  badge?: string;
  /** 单次预计消耗积分 */
  creditsCost: number;
}

/** 图片设置（分辨率 + 输出格式） */

/** 分辨率 */
export type ProductImageResolution = '1K' | '2K' | '4K';
export interface ResolutionOption {
  key: ProductImageResolution;
  /** 完整标签：标准 1K */
  label: string;
  /** 缩略标签：1K */
  short: string;
}

/** 输出格式 */
export type ProductImageFormat = 'PNG' | 'JPEG';
export interface FormatOption {
  key: ProductImageFormat;
  label: string;
}

/** 用户图片设置 */
export interface ProductImageSetting {
  key: string;
  resolution: ProductImageResolution;
  format: ProductImageFormat;
}

/** 提交分析请求 */
export interface CreateProductImageRequest {
  /** 商品图素材 id 列表（来自素材库或上传） */
  assetIds: string[];
  lang: ProductImageLang;
  count: ProductImageCount;
  /** 卖点方向 / 目标人群 / 禁用元素 / 风格 等补充说明 */
  brief?: string;
  modelKey: string;
  settingKey: ProductImageSetting['key'];
}

/** 提交分析后返回的订单/任务 */
export interface ProductImageTask {
  taskId: string;
  status: 'editing' | 'running' | 'success' | 'failed';
  /** 生成的图 URL 数组（完成后才有） */
  imageUrls?: string[];
  /** 任务用到的商品图（用于任务卡显示缩略图） */
  previewUrls?: string[];
  /** 预计/实际消耗积分 */
  creditsCost: number;
  createdAt: number;
  failReason?: string;
}

/** 参考示例（中间轮播用） */
export interface ProductImageExample {
  id: string;
  title: string;
  subtitle: string;
  description?: string;
  /** 图片 URL */
  imageUrl: string;
  /** 顺序 */
  order: number;
}
