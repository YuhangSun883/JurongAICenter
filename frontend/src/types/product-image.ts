/** 商品套图（商详）类型定义 —— 前端约定，后端按此实现 */

/** 语言选项 */
export type ProductImageLang = '中文' | 'English' | '英文' | '日文' | '韩文';

/** 张数选项 */
export type ProductImageCount = '4 张' | '6 张' | '8 张' | '9 张' | '12 张';

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
  /** 商品图内容列表（base64 data URI 或后端可访问 URL），图生图引用图来源 */
  images?: string[];
  lang: ProductImageLang;
  count: ProductImageCount;
  /** 卖点方向 / 目标人群 / 禁用元素 / 风格 等补充说明 */
  brief?: string;
  modelKey: string;
  settingKey: ProductImageSetting['key'];
  /** 分辨率：1K / 2K / 4K */
  resolution?: ProductImageResolution;
  /** 输出格式：PNG / JPEG */
  format?: ProductImageFormat;
  /** 自定义提示词（选填）：分析卡片「立即生成」传完整设计分析文案，后端单张生成模式 */
  prompt?: string;
  /** 指定套图类型（选填）：与 prompt 搭配，如 主视图 / 卖点图 */
  role?: string;
}

/** 提交分析后返回的订单/任务 */
export interface ProductImageTask {
  taskId: string;
  status: 'editing' | 'running' | 'success' | 'failed';
  /** 生成的图 URL 数组（完成后才有） */
  imageUrls?: string[];
  /** 每张生成图对应的套图类型（与 imageUrls 顺序一致，结果卡片标签用） */
  imageRoles?: string[];
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

/** 商品详解分析 —— 分析要点键值对（如 画布尺寸: xxx，键随用户语种输出） */
export interface ProductImageAnalysisSection {
  key: string;
  value: string;
}

/** 商品详解分析 —— 单张商详图的分析条目 */
export interface ProductImageAnalysisItem {
  /** 引用图标签：@图片1（对应上传顺序） */
  refLabel: string;
  /** 套图类型：主视图 / 卖点图 … */
  role: string;
  /** 画布比例：1:1 / 4:5 */
  ratio: string;
  /** 分析要点（有序） */
  sections: ProductImageAnalysisSection[];
}

/** 商品详解分析任务（POST /analysis 返回，前端轮询） */
export interface ProductImageAnalysisTask {
  taskId: string;
  status: 'running' | 'success' | 'failed';
  items?: ProductImageAnalysisItem[];
  createdAt: number;
  failReason?: string;
}

/** 单条分析文案重写请求（POST /analysis/refine）：按新定位与画布比例重写设计分析 */
export interface RefineAnalysisItemRequest {
  /** 商品图内容（单张）：base64 data URI 或 http(s) URL */
  image: string;
  /** 引用图标签：@图片1 */
  refLabel: string;
  /** 新定位（套图类型）：主视图 / 卖点图 / 收尾图 … */
  role: string;
  /** 画布比例：1:1 / 4:5 */
  ratio: string;
  /** 输出语言 */
  lang: string;
  /** 分辨率：1K / 2K / 4K */
  resolution: string;
  /** 补充说明（选填） */
  brief?: string;
}
