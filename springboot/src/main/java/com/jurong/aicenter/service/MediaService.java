package com.jurong.aicenter.service;

import com.jurong.aicenter.dto.media.MediaAssetDto;
import com.jurong.aicenter.dto.media.MediaLibraryDto;
import com.jurong.aicenter.dto.media.MediaRoleDto;
import com.jurong.aicenter.dto.media.UploadMediaResponse;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

public interface MediaService {

    // ============= 资产库 =============

    /** 拉用户资产库列表（自动建系统默认库 + 用户的 custom 库） */
    List<MediaLibraryDto> listLibraries(Long userId);

    // ============= 素材 =============

    /**
     * 拉素材列表（带筛选 + 分页）
     * @param libraryId null = 所有库
     * @param type      image/video/audio/null
     * @param source    uploaded/ai-generated/null
     * @param keyword   文件名模糊匹配
     */
    Map<String, Object> listAssets(Long userId, Long libraryId, String type,
                                   String source, String keyword,
                                   int page, int pageSize);

    MediaAssetDto getAsset(Long userId, Long assetId);

    /** 上传文件到 MinIO + 写入 media_assets */
    UploadMediaResponse upload(Long userId, Long libraryId, MultipartFile file);

    /** 删除素材（软删 + 删除 MinIO 对象） */
    void deleteAsset(Long userId, Long assetId);

    // ============= 角色库 =============

    /** 拉所有角色分类（用于前端下拉） */
    List<Map<String, String>> listCategories();

    /** 按分类拉角色列表 */
    List<MediaRoleDto> listRolesByCategory(String category);

    /** 拉全部角色（不分页，量小） */
    List<MediaRoleDto> listAllRoles();
}
import com.jurong.aicenter.dto.PageResult;
import com.jurong.aicenter.dto.media.MediaAssetResponse;
import com.jurong.aicenter.dto.media.MediaListQuery;
import com.jurong.aicenter.dto.media.MediaUploadResponse;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

public interface MediaService {

    /**
     * 分页查询素材（按用户隔离）
     */
    PageResult<MediaAssetResponse> listAssets(Long userId, MediaListQuery query);

    /**
     * 素材详情
     */
    MediaAssetResponse getAsset(Long userId, Long assetId);

    /**
     * 上传素材：未指定 libraryId 时自动进 "我的资产"
     */
    MediaUploadResponse uploadAsset(Long userId, Long libraryId, MultipartFile file);

    /**
     * 软删素材 + 删 MinIO 对象
     */
    void deleteAsset(Long userId, Long assetId);

    /**
     * 批量软删素材 + 删 MinIO 对象
     */
    int batchDeleteAssets(Long userId, List<Long> ids);

    /**
     * 改名
     */
    MediaAssetResponse renameAsset(Long userId, Long assetId, String name);

    /**
     * AI 任务完成时调用：写入素材到用户的 "AI 生成结果" 库
     */
    void recordAiGenerated(Long userId, String type, String filename,
                            String mimeType, Long sizeBytes, String objectKey,
                            String sourceTool, String sourceTaskId);

    /**
     * 删除整个库时调用：级联删素材 + MinIO
     */
    void deleteAssetsByLibrary(Long userId, Long libraryId);
}
