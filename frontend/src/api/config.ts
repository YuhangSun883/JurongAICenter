// API 中心配置 —— 新增功能时只改这一处
//
//   1) 在 types/ 新增类型
//   2) 在 api/<name>.real.ts 写真实后端调用
//   3) 在 api/<name>.mock.ts 写本地 mock
//   4) 在 api/<name>.ts 写 USE_MOCK 分发
//   5) 在下面的 APIS 数组里登记
//   6) 在 docs/API.md 文档里同步加表
//   7) 在 docs/CHANGELOG.md 写一条变更记录
//

/** 单一开关：true = 走前端 mock；false = 走真后端 */
export const USE_MOCK = process.env.NEXT_PUBLIC_USE_MOCK !== 'false';

/** 后端 API base（前端所有请求都走它）
 * 默认值与 springboot/src/main/resources/application.yml 的 server.port=8080 保持一致
 */
export const API_BASE_URL = process.env.NEXT_PUBLIC_API_BASE_URL || 'http://localhost:8080';

/** 已注册的业务域 —— 用来自动生成文档和健康检查 */
export const APIS = [
  'auth',       // 用户认证
  'agent',      // AI 聊天 + 积分 + 套餐 + 订单 + 客服 + 购买积分
  'video',      // 视频生成
  'media',      // 素材库 / 角色库 / 上传
  'creations',  // 统一创作入口（视频 / 图片 / Agent 三合一）
  'canvas',     // 画布节点 / 节点生成
  // 新增独立领域在这里加一行 ↓
  // 'image',    // 图片生成（示例）
] as const;

export type ApiDomain = (typeof APIS)[number];

/** 鉴权 token 在 localStorage 的 key（统一约定） */
export const TOKEN_KEY = 'token';
