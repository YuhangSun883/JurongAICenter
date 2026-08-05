// 业务侧统一入口
import { USE_MOCK } from './config';
import * as real from './media.real';
import * as mock from './media.mock';

export const mediaApi = {
  listAssets: (q?: Parameters<typeof real.listAssets>[0]) =>
    USE_MOCK ? mock.listAssets(q) : real.listAssets(q),
  deleteAsset: (id: string) =>
    USE_MOCK ? mock.deleteAsset(id) : real.deleteAsset(id),
  uploadAsset: (file: File) =>
    USE_MOCK ? mock.uploadAsset(file) : real.uploadAsset(file),
  listRoleCategories: () =>
    USE_MOCK ? mock.listRoleCategories() : real.listRoleCategories(),
  listRoles: (q?: Parameters<typeof real.listRoles>[0]) =>
    USE_MOCK ? mock.listRoles(q) : real.listRoles(q),
};
