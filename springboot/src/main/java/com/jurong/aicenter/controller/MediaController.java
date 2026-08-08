package com.jurong.aicenter.controller;

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
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

/**
 * 资产素材 REST API。
 *
 * <p>资产库相关（/api/media/libraries）见 {@link MediaLibraryController}。
 *
 * <p>端点列表：
 *   GET    /api/media/assets                 分页列表（按 userId 隔离）
 *   GET    /api/media/assets/{id}            素材详情
 *   POST   /api/media/assets                 上传（multipart/form-data，libraryId 可选）
 *   PATCH  /api/media/assets/{id}            改名
 *   DELETE /api/media/assets/{id}            删除（连 MinIO）
 *   POST   /api/media/assets/batch-delete    批量删除
 */
@RestController
@RequestMapping("/api/media/assets")
@RequiredArgsConstructor
public class MediaController {

    private final MediaService mediaService;

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