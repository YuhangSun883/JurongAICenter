package com.jurong.aicenter.service;

import com.jurong.aicenter.dto.PageResult;
import com.jurong.aicenter.dto.media.MediaAssetResponse;
import com.jurong.aicenter.dto.media.MediaListQuery;
import com.jurong.aicenter.dto.media.MediaUploadResponse;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * 媒体素材服务接口（资产库相关见 {@link MediaLibraryService}）。
 *
 * <p>关键设计：
 *   - 上传文件走 StorageService.uploadObject()，key 形如 "media/{userId}/{yyyy-MM}/{uuid}.{ext}"
 *   - 删除时同时软删 DB 行 + 删 MinIO 对象（最佳努力）
 *   - 严格按 userId 隔离
 */
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