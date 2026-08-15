package com.jurong.aicenter.controller;

import com.jurong.aicenter.dto.PageResult;
import com.jurong.aicenter.dto.media.BatchDeleteAssetsRequest;
import com.jurong.aicenter.dto.media.MediaAssetDto;
import com.jurong.aicenter.dto.media.MediaAssetResponse;
import com.jurong.aicenter.dto.media.MediaLibraryResponse;
import com.jurong.aicenter.dto.media.MediaListQuery;
import com.jurong.aicenter.dto.media.MediaRoleDto;
import com.jurong.aicenter.dto.media.MediaUploadResponse;
import com.jurong.aicenter.dto.media.PatchAssetRequest;
import com.jurong.aicenter.entity.MediaAsset;
import com.jurong.aicenter.exception.BusinessException;
import com.jurong.aicenter.exception.ErrorCode;
import com.jurong.aicenter.repository.MediaAssetRepository;
import com.jurong.aicenter.security.JwtAuthenticationFilter.AuthenticatedUser;
import com.jurong.aicenter.service.MediaLibraryService;
import com.jurong.aicenter.service.MediaService;
import com.jurong.aicenter.service.StorageService;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * 媒体资产 REST API（合并版：资产库 + 素材 + 角色库）
 *
 * <p>合并后的接口一览：
 * <pre>
 * 资产库
 *   GET    /api/media/libraries            拉资产库列表（系统默认 + 自定义）
 *
 * 素材
 *   GET    /api/media/assets               分页列表（按 userId 隔离，支持筛选）
 *   GET    /api/media/assets/{id}          素材详情
 *   POST   /api/media/assets               上传（multipart/form-data，libraryId 可选）
 *   PATCH  /api/media/assets/{id}          改名（同库内重名校验）
 *   DELETE /api/media/assets/{id}          删除（连 MinIO）
 *   POST   /api/media/assets/batch-delete  批量删除
 *
 * 角色库（同事设计，给画布/Agent 用）
 *   GET    /api/media/roles/categories     拉角色分类
 *   GET    /api/media/roles                拉角色列表（支持 ?category=xxx）
 * </pre>
 *
 * <p>说明：资产库的 CRUD（创建/重命名/删除）独立在 MediaLibraryController 里，
 * 路径前缀 /api/media/libraries/*。
 */
@Slf4j
@RestController
@RequestMapping("/api/media")
@RequiredArgsConstructor
public class MediaController {

    private final MediaService mediaService;
    private final MediaLibraryService mediaLibraryService;
    private final StorageService storageService;
    private final MediaAssetRepository assetRepository;

    // ==================== 资产库（资产库列表） ====================

    @GetMapping("/libraries")
    public List<MediaLibraryResponse> listLibraries(@AuthenticationPrincipal AuthenticatedUser user) {
        requireUser(user);
        return mediaLibraryService.listLibraries(user.id());
    }

    @GetMapping("/assets")
    public PageResult<MediaAssetResponse> list(
            @AuthenticationPrincipal AuthenticatedUser user,
            MediaListQuery query) {
        requireUser(user);
        return mediaService.listAssets(user.id(), query);
    }

    @GetMapping("/assets/{id}")
    public MediaAssetResponse get(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable Long id) {
        requireUser(user);
        return mediaService.getAsset(user.id(), id);
    }

    @GetMapping("/assets/{id}/stream")
    public ResponseEntity<InputStreamResource> stream(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable Long id,
            @RequestHeader(value = "Range", required = false) String rangeHeader) {
        requireUser(user);
        MediaAsset asset = assetRepository.selectById(id);
        if (asset == null || !asset.getUserId().equals(user.id())) {
            throw new BusinessException(ErrorCode.MEDIA_ASSET_NOT_FOUND);
        }
        String objectKey = asset.getObjectKey();
        if (objectKey == null || objectKey.isBlank()) {
            throw new BusinessException(ErrorCode.MEDIA_ASSET_NOT_FOUND);
        }
        try {
            long contentLength = asset.getSizeBytes() != null ? asset.getSizeBytes() : -1;
            InputStream is = storageService.getFileStream(objectKey);

            if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
                String[] ranges = rangeHeader.substring(6).split("-");
                long start = Long.parseLong(ranges[0]);
                long end = (ranges.length > 1 && !ranges[1].isBlank())
                        ? Long.parseLong(ranges[1]) : contentLength - 1;
                if (contentLength > 0 && end >= contentLength) {
                    end = contentLength - 1;
                }
                long len = end - start + 1;

                is.skip(start);

                String filename = asset.getName() != null ? asset.getName() : "media";
                String encodedName = URLEncoder.encode(filename, StandardCharsets.UTF_8).replace("+", "%20");
                return ResponseEntity.status(206)
                        .header(HttpHeaders.CONTENT_DISPOSITION,
                                "inline; filename=\"" + encodedName + "\"; filename*=UTF-8''" + encodedName)
                        .header("Accept-Ranges", "bytes")
                        .header("Content-Range", "bytes " + start + "-" + end + "/" + contentLength)
                        // V26：分片响应也带缓存头
                        .header(HttpHeaders.CACHE_CONTROL, "private, max-age=86400, immutable")
                        .contentLength(len)
                        .contentType(MediaType.parseMediaType(asset.getMimeType() != null
                                ? asset.getMimeType() : "application/octet-stream"))
                        .body(new InputStreamResource(is));
            }

            String filename = asset.getName() != null ? asset.getName() : "media";
            String encodedName = URLEncoder.encode(filename, StandardCharsets.UTF_8).replace("+", "%20");
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION,
                            "inline; filename=\"" + encodedName + "\"; filename*=UTF-8''" + encodedName)
                    .header("Accept-Ranges", "bytes")
                    // V26：媒体流加缓存头。URL 稳定（/api/assets/{id}/stream），资产 id 不变 → 浏览器可命中磁盘缓存。
                    // - private：仅浏览器本地缓存，禁 CDN/代理共享（资产归属当前用户，跨用户共享不安全）
                    // - max-age=86400：1 天内不重新请求
                    // - immutable：明确告诉浏览器资源不会变（资产更新会换 id，对应新 URL）
                    .header(HttpHeaders.CACHE_CONTROL, "private, max-age=86400, immutable")
                    .contentType(MediaType.parseMediaType(asset.getMimeType() != null
                            ? asset.getMimeType() : "application/octet-stream"))
                    .contentLength(contentLength)
                    .body(new InputStreamResource(is));
        } catch (Exception e) {
            log.error("Stream asset failed: id={}, objectKey={}", id, objectKey, e);
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "Failed to stream media: " + e.getMessage());
        }
    }

    @PostMapping("/assets")
    public MediaUploadResponse uploadAsset(
            @AuthenticationPrincipal AuthenticatedUser user,
            @RequestParam(value = "libraryId", required = false) Long libraryId,
            @RequestParam("file") MultipartFile file) {
        requireUser(user);
        return mediaService.uploadAsset(user.id(), libraryId, file);
    }

    @PostMapping("/upload")
    public MediaUploadResponse uploadLegacy(
            @AuthenticationPrincipal AuthenticatedUser user,
            @RequestParam(value = "libraryId", required = false) Long libraryId,
            @RequestParam("file") MultipartFile file) {
        requireUser(user);
        return mediaService.uploadAsset(user.id(), libraryId, file);
    }

    @PatchMapping("/assets/{id}")
    public MediaAssetResponse patch(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable Long id,
            @Valid @RequestBody PatchAssetRequest request) {
        requireUser(user);
        // 2026-08-15：PATCH 现在支持同时改 name 和/或 libraryId
        return mediaService.patchAsset(user.id(), id, request);
    }

    @DeleteMapping("/assets/{id}")
    public ResponseEntity<Void> delete(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable Long id) {
        requireUser(user);
        mediaService.deleteAsset(user.id(), id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/assets/batch-delete")
    public Map<String, Object> batchDelete(
            @AuthenticationPrincipal AuthenticatedUser user,
            @Valid @RequestBody BatchDeleteAssetsRequest request) {
        requireUser(user);
        int deleted = mediaService.batchDeleteAssets(user.id(), request.getIds());
        return Map.of("deleted", deleted, "requested", request.getIds().size());
    }

    @GetMapping("/roles/categories")
    public List<Map<String, String>> listRoleCategories(@AuthenticationPrincipal AuthenticatedUser user) {
        requireUser(user);
        return mediaService.listCategories();
    }

    @GetMapping("/roles")
    public List<MediaRoleDto> listRoles(
            @AuthenticationPrincipal AuthenticatedUser user,
            @RequestParam(required = false) String category) {
        requireUser(user);
        if (category == null || category.isBlank()) {
            return mediaService.listAllRoles();
        }
        return mediaService.listRolesByCategory(category);
    }

    private void requireUser(AuthenticatedUser user) {
        if (user == null || user.id() == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
    }
}
