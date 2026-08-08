// 媒体资产业务侧统一入口
// 走真实后端（USE_MOCK 由 .env.local 配置 NEXT_PUBLIC_USE_MOCK=false）
//
// mock 实现保留在 media.mock.ts，开发时若要切换可改这里：
//   import * as real from './media.real';
//   import * as mock from './media.mock';
//   import { USE_MOCK } from './config';
//   ... USE_MOCK ? mock.xxx() : real.xxx()

import * as real from './media.real';

export const mediaApi = {
  // 资产库
  listLibraries: () => real.listLibraries(),
  createLibrary: (req: Parameters<typeof real.createLibrary>[0]) => real.createLibrary(req),
  renameLibrary: (id: number, req: Parameters<typeof real.renameLibrary>[1]) => real.renameLibrary(id, req),
  deleteLibrary: (id: number) => real.deleteLibrary(id),

  // 素材
  listAssets: (q?: Parameters<typeof real.listAssets>[0]) => real.listAssets(q),
  getAsset: (id: number) => real.getAsset(id),
  renameAsset: (id: number, name: string) => real.renameAsset(id, name),
  deleteAsset: (id: number) => real.deleteAsset(id),
  batchDeleteAssets: (ids: number[]) => real.batchDeleteAssets(ids),
  uploadAsset: (file: File, libraryId?: number) => real.uploadAsset(file, libraryId),

  // 角色库（同事保留，给画布/Agent 用）
  listRoleCategories: () => real.listRoleCategories(),
  listRoles: (q?: Parameters<typeof real.listRoles>[0]) => real.listRoles(q),
};