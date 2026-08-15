package com.jurong.aicenter.service;

import com.jurong.aicenter.dto.media.CreateLibraryRequest;
import com.jurong.aicenter.dto.media.MediaLibraryResponse;
import com.jurong.aicenter.dto.media.RenameLibraryRequest;
import com.jurong.aicenter.entity.MediaLibrary;

import java.util.List;

public interface MediaLibraryService {

    /**
     * 列出当前用户所有资产库（含 2 个系统库 + custom 库）。
     * 2026-08-15 V19 调整：默认返回全部（含子库），并填充 hasChildren。
     * 前端如果只需要根库，可改调 listRootLibraries。
     */
    List<MediaLibraryResponse> listLibraries(Long userId);

    /**
     * 2026-08-15 V19: 只列根库（parent_id IS NULL）。
     */
    List<MediaLibraryResponse> listRootLibraries(Long userId);

    /**
     * 2026-08-15 V19: 列某父库下直接子库。
     */
    List<MediaLibraryResponse> listChildLibraries(Long userId, Long parentId);

    /**
     * 2026-08-15 V19: 取某库面包屑（root → ... → 自己），按 depth 从远到近。
     */
    List<MediaLibraryResponse> getBreadcrumb(Long userId, Long libraryId);

    /**
     * 新建 custom 资产库（系统库不可手动创建）。V19 起支持 parentId。
     */
    MediaLibraryResponse createLibrary(Long userId, CreateLibraryRequest request);

    /**
     * 重命名资产库（系统库不可重命名）。V19 起父库不可改。
     */
    MediaLibraryResponse renameLibrary(Long userId, Long libraryId, RenameLibraryRequest request);

    /**
     * 删除 custom 资产库，连同本库及所有后代库的素材一起删。
     * 系统库不能删。V19 起递归删除整棵子树。
     */
    void deleteLibrary(Long userId, Long libraryId);

    /**
     * 注册时调用：创建 2 个系统默认库（事务内）
     */
    void createDefaultLibraries(Long userId);

    /**
     * 取用户的"AI 生成结果"库
     */
    MediaLibrary getAiLibrary(Long userId);

    /**
     * 取用户的"我的资产"库；没有则自动建一个
     */
    MediaLibrary getOrCreateUploadLibrary(Long userId);
    default MediaLibrary getUploadLibrary(Long userId) {
        return getOrCreateUploadLibrary(userId);
    }

    /**
     * 取用户第一个 custom 库（按 sort_order 排序）；没有则建一个"未分类"
     */
    MediaLibrary getOrCreateDefaultCustom(Long userId);
}
