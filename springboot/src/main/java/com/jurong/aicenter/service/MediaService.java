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

    // ==================== 素材 ====================

    /**
     * 分页查询素材（按用户隔离）
     */
    PageResult<MediaAssetResponse> listAssets(Long userId, MediaListQuery query);

    /**
     * 素材详情
     */
    MediaAssetDto getAsset(Long userId, Long assetId);

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
     * 改名（同库内重名校验）
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

    // ==================== 角色库（同事保留，给画布/Agent 用） ====================

    /**
     * 拉所有角色分类（用于前端下拉）
     */
    List<Map<String, String>> listCategories();

    /**
     * 按分类拉角色列表
     */
    List<MediaRoleDto> listRolesByCategory(String category);

    /**
     * 拉全部角色（不分页，量小）
     */
    List<MediaRoleDto> listAllRoles();
}