package com.jurong.aicenter.controller;

import com.jurong.aicenter.entity.Job;
import com.jurong.aicenter.exception.BusinessException;
import com.jurong.aicenter.exception.ErrorCode;
import com.jurong.aicenter.security.JwtAuthenticationFilter.AuthenticatedUser;
import com.jurong.aicenter.service.VideoSyncService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 视频同步端点
 *
 * 用途：ComfyUI 节点的 JurongImageToVideo 在 save_video_file 阶段偶发失败，
 * 导致 outputs 为空，Spring Boot 端拿不到 video_path。
 * 此接口允许手动补救：传入 NewAPI task_id → 查状态 → 拿 URL → 下载上传到 MinIO。
 *
 * 端点：
 *   POST /api/video-sync/from-newapi   绑定 jobId（更新现有 job 的 resultUrls）
 *   POST /api/video-sync/standalone    独立模式（只下载上传，返回 URL）
 */
@RestController
@RequestMapping("/api/video-sync")
@RequiredArgsConstructor
public class VideoSyncController {

    private final VideoSyncService videoSyncService;

    @PostMapping("/from-newapi")
    public Map<String, Object> syncFromNewApi(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @RequestBody SyncRequest request) {
        if (principal == null) throw new BusinessException(ErrorCode.UNAUTHORIZED);
        if (request == null || request.getJobId() == null
            || request.getNewApiTaskId() == null || request.getNewApiTaskId().isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_PARAM, "jobId 和 newApiTaskId 必填");
        }
        Job job = videoSyncService.syncVideoFromNewApi(
            principal.id(), request.getJobId(), request.getNewApiTaskId());
        return Map.of(
            "jobId", job.getId(),
            "status", job.getStatus(),
            "resultUrls", job.getResultUrls() == null ? "[]" : job.getResultUrls()
        );
    }

    @PostMapping("/standalone")
    public Map<String, String> syncStandalone(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @RequestBody Map<String, String> request) {
        if (principal == null) throw new BusinessException(ErrorCode.UNAUTHORIZED);
        String newApiTaskId = request.get("newApiTaskId");
        if (newApiTaskId == null || newApiTaskId.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_PARAM, "newApiTaskId 必填");
        }
        String url = videoSyncService.syncVideoStandalone(principal.id(), newApiTaskId);
        return Map.of("url", url);
    }

    /** 内部 DTO */
    @lombok.Data
    public static class SyncRequest {
        private Long jobId;
        private String newApiTaskId;
    }
}
