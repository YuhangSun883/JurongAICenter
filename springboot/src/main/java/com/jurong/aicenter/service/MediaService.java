package com.jurong.aicenter.service;

import com.jurong.aicenter.dto.PageResult;
import com.jurong.aicenter.dto.media.MediaAssetDto;
import com.jurong.aicenter.dto.media.MediaAssetResponse;
import com.jurong.aicenter.dto.media.MediaListQuery;
import com.jurong.aicenter.dto.media.MediaRoleDto;
import com.jurong.aicenter.dto.media.MediaUploadResponse;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

/**
 * 媒体资产 Service 接口（合并版）
 *
 * <p>包含三大类：
 * <ul>
 *   <li>素材：listAssets / getAsset / uploadAsset / deleteAsset / batchDeleteAssets / renameAsset
 *   <li>AI 工具回调：recordAiGenerated / deleteAssetsByLibrary
 *   <li>角色库（同事保留）：listCategories / listAllRoles / listRolesByCategory
 * </ul>
 *
 * <p>资产库的 CRUD（创建/重命名/删除）由独立的 {@link MediaLibraryService} 提供。
 */
public interface MediaService {

    PageResult<MediaAssetResponse> listAssets(Long userId, MediaListQuery query);

    MediaAssetResponse getAsset(Long userId, Long assetId);

    MediaUploadResponse uploadAsset(Long userId, Long libraryId, MultipartFile file);

    void deleteAsset(Long userId, Long assetId);

    int batchDeleteAssets(Long userId, List<Long> ids);

    MediaAssetResponse renameAsset(Long userId, Long assetId, String name);

    List<Map<String, String>> listCategories();

    List<MediaRoleDto> listRolesByCategory(String category);

    List<MediaRoleDto> listAllRoles();

    void recordAiGenerated(Long userId, String type, String filename,
                           String mimeType, Long sizeBytes, String objectKey,
                           String sourceTool, String sourceTaskId);

    /**
     * 收藏图片 → 存入"我的资产"库（media_assets + MinIO）。
     * 不单独用 favorites/ 目录，直接按 media/{userId}/{yyyy-MM}/{uuid}.{ext} 存。
     *
     * @param userId      用户 ID
     * @param imageBytes  图片字节
     * @param mimeType    MIME 类型（image/png、image/jpeg 等）
     * @param displayName 用户可见的文件名（可为 null，默认自动生成）
     * @return 资产响应（含 assetId、URL、名称、类型、大小）
     */
    MediaAssetResponse saveFavoriteAsAsset(Long userId, byte[] imageBytes, String mimeType, String displayName);

    /**
     * 通过 userId + sourceTaskId + filename（name 字段）反查 AI 产物的 objectKey。
     * 找不到则返回 null。
     */
    String lookupAiMediaObjectKey(Long userId, String sourceTaskId, String filename);

    void deleteAssetsByLibrary(Long userId, Long libraryId);
}