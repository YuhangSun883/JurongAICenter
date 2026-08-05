// 通用响应包装（后端按这个格式回最方便）
export interface ApiResult<T> {
  code: number;
  message?: string;
  data: T;
}

/** 分页请求基础 */
export interface PageQuery {
  page?: number;
  pageSize?: number;
}

/** 分页响应 */
export interface PageResult<T> {
  items: T[];
  total: number;
  page: number;
  pageSize: number;
}
