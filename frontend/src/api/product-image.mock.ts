import type {
  CreateProductImageRequest,
  FormatOption,
  ProductImageExample,
  ProductImageModel,
  ProductImageTask,
  ResolutionOption,
} from '@/types/product-image';

const delay = <T>(v: T, ms = 200) => new Promise<T>((r) => setTimeout(() => r(v), ms));

const MODELS: ProductImageModel[] = [
  { key: 'standard', label: '高级版', creditsCost: 100 },
  { key: 'premium', label: '高级版 VIP', badge: '更快更稳', creditsCost: 200 },
];

const RESOLUTIONS: ResolutionOption[] = [
  { key: '1K', label: '标准 1K', short: '1K' },
  { key: '2K', label: '高清 2K', short: '2K' },
  { key: '4K', label: '超清 4K', short: '4K' },
];

const FORMATS: FormatOption[] = [
  { key: 'PNG', label: 'PNG' },
  { key: 'JPEG', label: 'JPEG' },
];

/** mock 参考示例（用在线占位图，避免本地依赖） */
const EXAMPLES: ProductImageExample[] = [
  {
    id: 'ex-1',
    title: '南洋套图参考示例',
    subtitle: '首页 / 主视觉',
    description: '用清晰主体、品牌氛围和核心利益点，快速建立商品第一印象。',
    imageUrl: 'https://images.unsplash.com/photo-1592945403244-b3fbafd7f539?w=720&auto=format&fit=crop',
    order: 1,
  },
  {
    id: 'ex-2',
    title: '产品细节图',
    subtitle: '材质 / 工艺',
    description: '放大产品纹理与工艺亮点，提升详情页的专业感。',
    imageUrl: 'https://images.unsplash.com/photo-1542295669297-4d352b042bca?w=720&auto=format&fit=crop',
    order: 2,
  },
  {
    id: 'ex-3',
    title: '使用场景图',
    subtitle: '场景化 / 情感代入',
    description: '把商品融入生活场景，激发用户的使用联想与购买欲。',
    imageUrl: 'https://images.unsplash.com/photo-1556228720-195a672e8a03?w=720&auto=format&fit=crop',
    order: 3,
  },
  {
    id: 'ex-4',
    title: '对比卖点图',
    subtitle: '差异 / 优势',
    description: '通过对比突出核心卖点，强化用户对价值的认知。',
    imageUrl: 'https://images.unsplash.com/photo-1571781926291-c477ebfd024b?w=720&auto=format&fit=crop',
    order: 4,
  },
  {
    id: 'ex-5',
    title: '成分溯源图',
    subtitle: '成分 / 安全',
    description: '用可视化展示成分与安全检测，建立信任感。',
    imageUrl: 'https://images.unsplash.com/photo-1620916566398-39f1143ab7be?w=720&auto=format&fit=crop',
    order: 5,
  },
  {
    id: 'ex-6',
    title: '包装与赠品',
    subtitle: '包装 / 赠品',
    description: '呈现包装与赠品细节，塑造超值感与仪式感。',
    imageUrl: 'https://images.unsplash.com/photo-1607082348824-0a96f2a4b9da?w=720&auto=format&fit=crop',
    order: 6,
  },
  {
    id: 'ex-7',
    title: '权威背书图',
    subtitle: '资质 / 认证',
    description: '展示资质证书或品牌认证，提升权威性与可信度。',
    imageUrl: 'https://images.unsplash.com/photo-1560472354-b33ff0c44a43?w=720&auto=format&fit=crop',
    order: 7,
  },
  {
    id: 'ex-8',
    title: '用户口碑图',
    subtitle: 'UGC / 评价',
    description: '用真实用户口碑佐证产品效果，增强转化率。',
    imageUrl: 'https://images.unsplash.com/photo-1517245386807-bb43f82c33c4?w=720&auto=format&fit=crop',
    order: 8,
  },
];

export async function listModels(): Promise<ProductImageModel[]> {
  return delay(MODELS);
}
export async function listResolutions(): Promise<ResolutionOption[]> {
  return delay(RESOLUTIONS);
}

export async function listFormats(): Promise<FormatOption[]> {
  return delay(FORMATS);
}
export async function listExamples(): Promise<ProductImageExample[]> {
  return delay(EXAMPLES);
}

/** mock 任务：返回 editing，由调用方轮询 */
export async function createProductImageTask(req: CreateProductImageRequest): Promise<ProductImageTask> {
  const model = MODELS.find((m) => m.key === req.modelKey) ?? MODELS[0];
  return delay({
    taskId: 'pit_' + Math.random().toString(36).slice(2, 10),
    status: 'editing',
    creditsCost: model.creditsCost,
    createdAt: Date.now(),
  });
}

export async function getProductImageTask(taskId: string): Promise<ProductImageTask> {
  // mock：第一次查 editing，第二次查 running，第三次查 success
  const map = (typeof globalThis !== 'undefined' ? (globalThis as { __PI_TASK_MAP?: Record<string, number> }) : {}).__PI_TASK_MAP ?? {};
  const n = (map[taskId] ?? 0) + 1;
  if (typeof globalThis !== 'undefined') {
    (globalThis as { __PI_TASK_MAP?: Record<string, number> }).__PI_TASK_MAP = { ...map, [taskId]: n };
  }
  const status: ProductImageTask['status'] = n === 1 ? 'editing' : n === 2 ? 'running' : 'success';
  return delay({
    taskId,
    status,
    creditsCost: 100,
    createdAt: Date.now() - n * 1000,
    imageUrls: status === 'success' ? EXAMPLES.slice(0, 4).map((e) => e.imageUrl) : undefined,
  });
}
