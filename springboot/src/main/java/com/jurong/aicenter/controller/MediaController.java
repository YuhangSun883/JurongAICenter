package com.jurong.aicenter.controller;

import com.jurong.aicenter.dto.media.MediaAssetDto;
import com.jurong.aicenter.dto.media.MediaLibraryDto;
import com.jurong.aicenter.dto.media.MediaRoleDto;
import com.jurong.aicenter.dto.media.UploadMediaResponse;
import com.jurong.aicenter.dto.PageResult;
import com.jurong.aicenter.dto.media.BatchDeleteAssetsRequest;
import com.jurong.aicenter.dto.media.MediaAssetResponse;
import com.jurong.aicenter.dto.media.MediaListQuery;
import com.jurong.aicenter.dto.media.MediaUploadResponse;
import com.jurong.aicenter.dto.media.PatchAssetRequest;
import com.jurong.aicenter.exception.BusinessException;
import com.jurong.aicenter.exception.ErrorCode;
import com.jurong.aicenter.security.JwtAuthenticationFilter.AuthenticatedUser;
import com.jurong.aicenter.service.MediaService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

/**
 * 媒体资产 REST API
 *
 * 端点:
 *   GET    /api/media/libraries                 拉资产库列表
 *   GET    /api/media/assets                    拉素材列表(带筛选/分页)
 *   GET    /api/media/assets/{id}               单条素材
 *   POST   /api/media/upload                    上传文件(multipart)
 *   DELETE /api/media/assets/{id}               删除素材
 *   GET    /api/media/roles/categories          拉角色分类
 *   GET    /api/media/roles                     拉全部角色
 *   GET    /api/media/roles?category=xxx        按分类拉角色
 */
@Slf4j
@RestController
@RequestMapping("/api/media")
 * 资产素材 REST API。
 *
 * 端点列表：
 *   GET    /api/media/assets           分页列表（按 userId 隔离）
 *   GET    /api/media/assets/{id}      素材详情
 *   POST   /api/media/assets           上传（multipart/form-data，libraryId 可选）
 *   PATCH  /api/media/assets/{id}      改名
 *   DELETE /api/media/assets/{id}      删除（连 MinIO）
 *   POST   /api/media/assets/batch-delete  批量删除
 */
@RestController
@RequestMapping("/api/media/assets")
@RequiredArgsConstructor
public class MediaController {

    private final MediaService mediaService;

    @GetMapping("/libraries")
    public List<MediaLibraryDto> listLibraries(@AuthenticationPrincipal AuthenticatedUser user) {
        requireUser(user);
        return mediaService.listLibraries(user.id());
    }

    @GetMapping("/assets")
    public Map<String, Object> listAssets(
            @AuthenticationPrincipal AuthenticatedUser user,
            @RequestParam(required = false) Long libraryId,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String source,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false, defaultValue = "1") int page,
            @RequestParam(required = false, defaultValue = "24") int pageSize) {
        requireUser(user);
        return mediaService.listAssets(user.id(), libraryId, type, source, keyword, page, pageSize);
    }

    @GetMapping("/assets/{id}")
    public MediaAssetDto getAsset(
    @GetMapping
    public PageResult<MediaAssetResponse> list(
            @AuthenticationPrincipal AuthenticatedUser user,
            MediaListQuery query) {
        requireUser(user);
        return mediaService.listAssets(user.id(), query);
    }

    @GetMapping("/{id}")
    public MediaAssetResponse get(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable Long id) {
        requireUser(user);
        return mediaService.getAsset(user.id(), id);
    }

    @PostMapping("/upload")
    public UploadMediaResponse upload(
            @AuthenticationPrincipal AuthenticatedUser user,
            @RequestParam(required = false) Long libraryId,
            @RequestParam("file") MultipartFile file) {
        requireUser(user);
        return mediaService.upload(user.id(), libraryId, file);
    }

    @DeleteMapping("/assets/{id}")
    public Map<String, Object> deleteAsset(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable Long id) {
        requireUser(user);
        mediaService.deleteAsset(user.id(), id);
        return Map.of("assetId", id, "status", "deleted");
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
    @PostMapping
    public MediaUploadResponse upload(
            @AuthenticationPrincipal AuthenticatedUser user,
            @RequestParam(value = "libraryId", required = false) Long libraryId,
            @RequestParam("file") MultipartFile file) {
        requireUser(user);
        return mediaService.uploadAsset(user.id(), libraryId, file);
    }

    @PatchMapping("/{id}")
    public MediaAssetResponse rename(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable Long id,
            @Valid @RequestBody PatchAssetRequest request) {
        requireUser(user);
        return mediaService.renameAsset(user.id(), id, request.getName());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable Long id) {
        requireUser(user);
        mediaService.deleteAsset(user.id(), id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/batch-delete")
    public Map<String, Object> batchDelete(
            @AuthenticationPrincipal AuthenticatedUser user,
            @Valid @RequestBody BatchDeleteAssetsRequest request) {
        requireUser(user);
        int deleted = mediaService.batchDeleteAssets(user.id(), request.getIds());
        return Map.of("deleted", deleted, "requested", request.getIds().size());
    }

    private void requireUser(AuthenticatedUser user) {
        if (user == null || user.id() == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
    }
}
}
