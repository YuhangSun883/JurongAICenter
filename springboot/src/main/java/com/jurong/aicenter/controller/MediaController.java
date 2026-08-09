package com.jurong.aicenter.controller;

import com.jurong.aicenter.dto.PageResult;
import com.jurong.aicenter.dto.media.BatchDeleteAssetsRequest;
import com.jurong.aicenter.dto.media.MediaAssetResponse;
import com.jurong.aicenter.dto.media.MediaListQuery;
import com.jurong.aicenter.dto.media.MediaRoleDto;
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

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/media")
@RequiredArgsConstructor
public class MediaController {

    private final MediaService mediaService;

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
    public MediaAssetResponse rename(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable Long id,
            @Valid @RequestBody PatchAssetRequest request) {
        requireUser(user);
        return mediaService.renameAsset(user.id(), id, request.getName());
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
