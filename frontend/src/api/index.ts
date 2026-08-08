// 业务侧 API 统一出口 —— 业务代码只 import 这个文件
// 不要再直接 import ./video.real / ./agent.mock 等内部文件

export { USE_MOCK, API_BASE_URL, APIS, TOKEN_KEY, type ApiDomain } from './config';
export { authApi } from './auth';
export { agentApi } from './agent';
export { videoApi } from './video';
export { mediaApi } from './media';
export { canvasApi } from './canvas';
export { watermarkApi } from './watermark';
export { imageApi } from './image';
export { promptApi } from './prompt';

// 新增功能在这里加一行 export ↓

// 通用类型
export type { ApiResult, PageQuery, PageResult } from '@/types/api';
