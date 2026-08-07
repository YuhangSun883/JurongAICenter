// 媒体资产业务侧统一入口
// （不再支持 mock,统一走真实后端）
import * as real from './media.real';

export const mediaApi = {
  listLibraries: real.listLibraries,
  listAssets: real.listAssets,
  getAsset: real.getAsset,
  upload: real.upload,
  deleteAsset: real.deleteAsset,
  listRoleCategories: real.listRoleCategories,
  listRoles: real.listRoles,
};