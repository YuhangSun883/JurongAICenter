// 与后端约定好的请求/响应类型。前端不写后端，但所有字段就是接口契约。

/** 视频模型枚举 */
export type VideoModel =
  | 'Seedance-2.0-VIP'
  | 'Seedance-2.0-Fast-VIP'
  | 'Seedance-2.0-Mini-VIP';

/** 视频宽高比 */
export type AspectRatio = '21:9' | '16:9' | '4:3' | '1:1' | '3:4' | '9:16';

/** 视频分辨率 */
export type Resolution = '480p' | '720p' | '1080p';

/** 视频时长（秒） */
export type Duration =
  | 4
  | 5
  | 6
  | 7
  | 8
  | 9
  | 10
  | 11
  | 12
  | 13
  | 14
  | 15
  | 16
  | 17
  | 18
  | 19
  | 20
  | 21
  | 22
  | 23
  | 24
  | 25
  | 26
  | 27
  | 28
  | 29
  | 30;

/** 生成视频的音频模式 */
export type AudioMode = 'with-audio' | 'mute';

/** 任务状态 */
export type TaskStatus =
  | 'queued' // 排队中
  | 'running' // 生成中
  | 'succeeded' // 完成
  | 'failed'; // 失败

/** 参考素材（视频/音频/图片） */
export interface ReferenceMedia {
  id: string;
  type: 'video' | 'audio' | 'image';
  url: string;
  name: string;
  /** @引用时的 token */
  token: string;
}

/** 视频生成请求体 */
export interface CreateVideoRequest {
  script: string;
  model: VideoModel;
  aspectRatio: AspectRatio;
  resolution: Resolution;
  duration: Duration;
  audioMode?: AudioMode;
  /** 关联的参考素材 id 列表 */
  referenceIds: string[];
}

/** 视频生成任务 */
export interface VideoTask {
  id: string;
  status: TaskStatus;
  progress: number; // 0-100
  request: CreateVideoRequest;
  /** 生成结果的视频 url（成功时） */
  resultUrl?: string;
  /** 缩略图 url */
  thumbnailUrl?: string;
  /** 失败原因 */
  error?: string;
  /** 预计消耗积分 */
  estimatedCredits: number;
  createdAt: number;
  updatedAt: number;
}

/** 创建任务响应 */
export interface CreateVideoResponse {
  taskId: string;
  estimatedCredits: number;
}

/** 点击「帮我写」时，请后端模型生成视频脚本 */
export interface GenerateVideoScriptRequest {
  brief?: string;
  model: VideoModel;
  aspectRatio: AspectRatio;
  duration: Duration;
  audioMode?: AudioMode;
  referenceIds: string[];
}

export interface GenerateVideoScriptResponse {
  script: string;
  model?: string;
  creditsEstimated?: number;
}

/** 列表查询参数 */
export interface ListTasksQuery {
  status?: TaskStatus;
  page?: number;
  pageSize?: number;
}

/** 列表查询响应 */
export interface ListTasksResponse {
  items: VideoTask[];
  total: number;
}
