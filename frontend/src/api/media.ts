// 业务侧统一入口
import { USE_MOCK } from './config';
import * as real from './media.real';
import * as mock from './media.mock';

export const mediaApi = {
  // 资产库
  listLibraries: () =>
    USE_MOCK ? mock.listLibraries() : real.listLibraries(),
  createLibrary: (req: Parameters<typeof real.createLibrary>[0]) =>
    USE_MOCK ? mock.createLibrary(req) : real.createLibrary(req),
  renameLibrary: (id: number, req: Parameters<typeof real.renameLibrary>[1]) =>
    USE_MOCK ? mock.renameLibrary(id, req) : real.renameLibrary(id, req),
  deleteLibrary: (id: number) =>
    USE_MOCK ? mock.deleteLibrary(id) : real.deleteLibrary(id),

  // 素材
  listAssets: (q?: Parameters<typeof real.listAssets>[0]) =>
    USE_MOCK ? mock.listAssets(q) : real.listAssets(q),
  getAsset: (id: number) =>
    USE_MOCK ? mock.getAsset(id) : real.getAsset(id),
  renameAsset: (id: number, name: string) =>
    USE_MOCK ? mock.renameAsset(id, name) : real.renameAsset(id, name),
  deleteAsset: (id: number) =>
    USE_MOCK ? mock.deleteAsset(id) : real.deleteAsset(id),
  batchDeleteAssets: (ids: number[]) =>
    USE_MOCK ? mock.batchDeleteAssets(ids) : real.batchDeleteAssets(ids),
  uploadAsset: (file: File, libraryId?: number) =>
    USE_MOCK ? mock.uploadAsset(file, libraryId) : real.uploadAsset(file, libraryId),

  // 角色库（兼容旧）
  listRoleCategories: () =>
    USE_MOCK ? mock.listRoleCategories() : real.listRoleCategories(),
  listRoles: (q?: Parameters<typeof real.listRoles>[0]) =>
    USE_MOCK ? mock.listRoles(q) : real.listRoles(q),
};
