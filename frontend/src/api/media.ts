import * as real from './media.real';

export const mediaApi = {
  listLibraries: () => real.listLibraries(),
  createLibrary: (req: Parameters<typeof real.createLibrary>[0]) => real.createLibrary(req),
  renameLibrary: (id: number, req: Parameters<typeof real.renameLibrary>[1]) => real.renameLibrary(id, req),
  deleteLibrary: (id: number) => real.deleteLibrary(id),

  listAssets: (q?: Parameters<typeof real.listAssets>[0]) => real.listAssets(q),
  getAsset: (id: number) => real.getAsset(id),
  renameAsset: (id: number, name: string) => real.renameAsset(id, name),
  deleteAsset: (id: number) => real.deleteAsset(id),
  batchDeleteAssets: (ids: number[]) => real.batchDeleteAssets(ids),
  uploadAsset: (file: File, libraryId?: number) => real.uploadAsset(file, libraryId),
  upload: (file: File, libraryId?: number) => real.uploadAsset(file, libraryId),

  listRoleCategories: () => real.listRoleCategories(),
  listRoles: (q?: Parameters<typeof real.listRoles>[0]) => real.listRoles(q),
};
