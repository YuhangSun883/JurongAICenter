package com.jurong.aicenter.service;

import com.jurong.aicenter.dto.PageResult;
import com.jurong.aicenter.dto.media.MediaAssetResponse;
import com.jurong.aicenter.dto.media.MediaListQuery;
import com.jurong.aicenter.dto.media.MediaRoleDto;
import com.jurong.aicenter.dto.media.MediaUploadResponse;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

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

    void deleteAssetsByLibrary(Long userId, Long libraryId);
}
