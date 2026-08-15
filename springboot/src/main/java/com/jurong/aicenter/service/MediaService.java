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

    /**
     * 2026-08-15：通用 PATCH 接口，同时支持改 name 和/或 libraryId。
     * 至少要传一个字段，否则抛 INVALID_PARAM。
     */
    MediaAssetResponse patchAsset(Long userId, Long assetId, com.jurong.aicenter.dto.media.PatchAssetRequest request);

    List<Map<String, String>> listCategories();

    List<MediaRoleDto> listRolesByCategory(String category);

    List<MediaRoleDto> listAllRoles();

    void recordAiGenerated(Long userId, String type, String filename,
                           String mimeType, Long sizeBytes, String objectKey,
                           String sourceTool, String sourceTaskId);

    /**
     * AI 图片工作台同步生成的图片入库（保存到 media_assets + MinIO）。
     * 与 saveFavoriteAsAsset 的区别：sourceTool=image（用于"预览"Tab），不依赖 ComfyUI 异步 job。
     *
     * @param userId      用户 ID
     * @param imageBytes  图片字节
     * @param mimeType    MIME 类型
     * @return 资产响应（含 assetId、URL、名称）
     */
    MediaAssetResponse recordGeneratedImage(Long userId, byte[] imageBytes, String mimeType);

    /**
     * 把已有 AI 生成图片标记为"已收藏"：UPDATE media_assets.source_tool = 'favorite' WHERE user_id=? AND object_key=?
     * 不上传、不复制图片（MinIO 中文件保持原样）。
     *
     * @return 更新后的资产记录（包含新 source_tool）
     * @throws BusinessException ASSET_NOT_FOUND 如果用户没有该 objectKey 的图片
     */
    MediaAssetResponse markAsFavorite(Long userId, String objectKey);

    /**
     * 撤销收藏：把 source_tool 改回 'image'（从收藏 Tab 移到预览 Tab）
     */
    MediaAssetResponse unmarkAsFavorite(Long userId, String objectKey);

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

    /**
     * Agent 模块用：根据素材 ID 列表取图片 URL（多模态 LLM 用）。
     *
     * <p>过滤规则：
     * <ul>
     *   <li>只返回当前用户拥有的素材（userId 校验）</li>
     *   <li>只返回 type=image 的素材（视频/音频不传 LLM）</li>
     * </ul>
     *
     * @param userId 当前用户 ID
     * @param ids    素材 ID 列表（可为 null/empty）
     * @return 图片 URL 列表（顺序与输入无关，按 id 去重）
     */
    List<String> getImageUrlsByIds(Long userId, List<String> ids);

    /**
     * Agent 模块用：根据素材 ID 列表取完整素材详情（含 url/name/type）。
     * 用于 agent 消息气泡里显示用户上传的图片。
     *
     * <p>注意：返回的 url 是临时签名 URL（24h 有效）。
     */
    List<com.jurong.aicenter.entity.MediaAsset> getAssetsByIds(Long userId, List<String> ids);

    /**
     * 取 MinIO 预签名 URL（24h 有效）。给前端显示用。
     */
    String getPresignedUrl(String objectKey, int hours);
}