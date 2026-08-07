package com.jurong.aicenter.service;

import com.jurong.aicenter.dto.media.CreateLibraryRequest;
import com.jurong.aicenter.dto.media.MediaLibraryResponse;
import com.jurong.aicenter.dto.media.RenameLibraryRequest;

import java.util.List;

public interface MediaLibraryService {

    /**
     * 列出当前用户所有资产库（含 2 个系统库 + custom 库）
     */
    List<MediaLibraryResponse> listLibraries(Long userId);

    /**
     * 新建 custom 资产库（系统库不可手动创建）
     */
    MediaLibraryResponse createLibrary(Long userId, CreateLibraryRequest request);

    /**
     * 重命名资产库（系统库不可重命名）
     */
    MediaLibraryResponse renameLibrary(Long userId, Long libraryId, RenameLibraryRequest request);

    /**
     * 删除 custom 资产库，连同库内素材 + MinIO 对象
     */
    void deleteLibrary(Long userId, Long libraryId);

    /**
     * 注册时调用：创建 2 个系统默认库（事务内）
     */
    void createDefaultLibraries(Long userId);

    /**
     * 拿用户的"AI 生成结果"库
     */
    com.jurong.aicenter.entity.MediaLibrary getAiLibrary(Long userId);

    /**
     * 拿用户的"我的资产"库
     */
    com.jurong.aicenter.entity.MediaLibrary getUploadLibrary(Long userId);

    /**
     * 拿用户首个 custom 库（按 sort_order 排序）；没有则建一个 "未分类"
     */
    com.jurong.aicenter.entity.MediaLibrary getOrCreateDefaultCustom(Long userId);
}
