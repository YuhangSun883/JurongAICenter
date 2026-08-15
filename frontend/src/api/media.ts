import * as real from './media.real';

export const mediaApi = {
  listLibraries: () => real.listLibraries(),
  /** 2026-08-15 V19：只列根库 */
  listRootLibraries: () => real.listRootLibraries(),
  /** 2026-08-15 V19：列某父库下直接子库 */
  listChildLibraries: (parentId: number) => real.listChildLibraries(parentId),
  /** 2026-08-15 V19：取某库面包屑（root → ... → 当前） */
  getLibraryBreadcrumb: (libraryId: number) => real.getLibraryBreadcrumb(libraryId),
  createLibrary: (req: Parameters<typeof real.createLibrary>[0]) => real.createLibrary(req),
  renameLibrary: (id: number, req: Parameters<typeof real.renameLibrary>[1]) => real.renameLibrary(id, req),
  deleteLibrary: (id: number) => real.deleteLibrary(id),

  listAssets: (q?: Parameters<typeof real.listAssets>[0]) => real.listAssets(q),
  getAsset: (id: number) => real.getAsset(id),
  renameAsset: (id: number, name: string) => real.renameAsset(id, name),
  patchAsset: (id: number, payload: { name?: string; libraryId?: number | null }) =>
    real.patchAsset(id, payload),
  deleteAsset: (id: number) => real.deleteAsset(id),
  batchDeleteAssets: (ids: number[]) => real.batchDeleteAssets(ids),
  uploadAsset: (file: File, libraryId?: number) => real.uploadAsset(file, libraryId),
  upload: (file: File, libraryId?: number) => real.uploadAsset(file, libraryId),

  listRoleCategories: () => real.listRoleCategories(),
  listRoles: (q?: Parameters<typeof real.listRoles>[0]) => real.listRoles(q),
};
