package com.jurong.aicenter.controller;

import com.jurong.aicenter.dto.media.CreateLibraryRequest;
import com.jurong.aicenter.dto.media.MediaLibraryResponse;
import com.jurong.aicenter.dto.media.RenameLibraryRequest;
import com.jurong.aicenter.exception.BusinessException;
import com.jurong.aicenter.exception.ErrorCode;
import com.jurong.aicenter.security.JwtAuthenticationFilter.AuthenticatedUser;
import com.jurong.aicenter.service.MediaLibraryService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 资产库 REST API。
 *
 * <p>端点列表：
 * <pre>
 *   GET    /api/media/libraries                       列出我的所有库 — 见 MediaController.listLibraries()
 *   GET    /api/media/libraries/roots                 只列根库（V19）
 *   GET    /api/media/libraries/{id}/children         列某库直接子库（V19）
 *   GET    /api/media/libraries/{id}/breadcrumb       取某库面包屑（V19）
 *   POST   /api/media/libraries                       新建 custom 库（可传 parentId 建子库）
 *   PATCH  /api/media/libraries/{id}                  重命名（系统库不可改，父库不可改）
 *   DELETE /api/media/libraries/{id}                  删除 custom 库（连子库、孙库、素材一起删）
 * </pre>
 *
 * <p>说明：GET 列表统一在 MediaController 里（那里也用到 libraries 列表）；
 * 本 controller 只负责写操作（创建/重命名/删除）+ 父子库相关读操作。
 */
@RestController
@RequestMapping("/api/media/libraries")
@RequiredArgsConstructor
public class MediaLibraryController {

    private final MediaLibraryService libraryService;

    @GetMapping("/roots")
    public List<MediaLibraryResponse> listRoots(@AuthenticationPrincipal AuthenticatedUser user) {
        requireUser(user);
        return libraryService.listRootLibraries(user.id());
    }

    @GetMapping("/{id}/children")
    public List<MediaLibraryResponse> listChildren(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable Long id) {
        requireUser(user);
        return libraryService.listChildLibraries(user.id(), id);
    }

    @GetMapping("/{id}/breadcrumb")
    public List<MediaLibraryResponse> getBreadcrumb(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable Long id) {
        requireUser(user);
        return libraryService.getBreadcrumb(user.id(), id);
    }

    @PostMapping
    public MediaLibraryResponse create(
            @AuthenticationPrincipal AuthenticatedUser user,
            @Valid @RequestBody CreateLibraryRequest request) {
        requireUser(user);
        return libraryService.createLibrary(user.id(), request);
    }

    @PatchMapping("/{id}")
    public MediaLibraryResponse rename(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable Long id,
            @Valid @RequestBody RenameLibraryRequest request) {
        requireUser(user);
        return libraryService.renameLibrary(user.id(), id, request);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable Long id) {
        requireUser(user);
        libraryService.deleteLibrary(user.id(), id);
        return ResponseEntity.noContent().build();
    }

    private void requireUser(AuthenticatedUser user) {
        if (user == null || user.id() == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
    }
}
