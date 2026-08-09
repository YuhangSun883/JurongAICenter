package com.jurong.aicenter.controller;


import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.jurong.aicenter.dto.generation.GenerateResponse;
import com.jurong.aicenter.exception.BusinessException;
import com.jurong.aicenter.exception.ErrorCode;
import com.jurong.aicenter.security.JwtAuthenticationFilter.AuthenticatedUser;
import com.jurong.aicenter.service.VideoGenerationService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

/**
 * 视频生成端点（图生视频）— 走 NewAPI 中转站，绕过 ComfyUI。
 *
 * <p>严格按 Assets-API 参考手册 §5 端到端流程：
 * 上传图片到 proxy → 轮询 asset active → 调 NewAPI /v1/videos → 异步轮询视频任务。
 *
 * <p>端点：
 * <pre>
 *   POST /api/video/image-to-video   (multipart/form-data)
 *     参数：file (图片, 必填) + prompt (提示词, 必填)
 *           + duration (秒, 可选, 默认 4)
 *           + resolution (480P/720P, 可选, 默认 480P)
 *     返回：{jobId, status, promptId}  （status=RUNNING, promptId=NewAPI task_id）
 * </pre>
 *
 * <p>查询/下载复用现有端点：
 * <ul>
 *   <li>{@code GET /api/jobs/{id}} 查任务状态（COMPLETED 时 resultUrls 含 MinIO URL）</li>
 *   <li>{@code GET /api/jobs/{id}/result/{filename}} 302 跳转到 MinIO 签名 URL</li>
 *   <li>{@code DELETE /api/jobs/{id}} 取消/删除任务</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/video")
@RequiredArgsConstructor
public class VideoGenerationController {
    // 2026-08-09 显式 log 字段(替代 @Slf4j,兼容 lombok 不跑的环境)
    private static final Logger log = LoggerFactory.getLogger(VideoGenerationController.class);

    private final VideoGenerationService videoGenerationService;

    /**
     * 图生视频：上传图片 + 提示词 → 异步生成视频。
     *
     * <p>同步返回 jobId（status=RUNNING），前端轮询 GET /api/jobs/{id} 拿结果。
     */
    @PostMapping("/image-to-video")
    public GenerateResponse imageToVideo(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @RequestParam("file") MultipartFile file,
            @RequestParam("prompt") String prompt,
            @RequestParam(value = "duration", defaultValue = "4") int duration,
            @RequestParam(value = "resolution", defaultValue = "480p") String resolution) {

        // 入口日志：记录全部关键参数（不含图片字节本身，只记大小和元信息）
        log.info("[I2V-REQ] 收到图生视频请求: userId={}, filename={}, contentType={}, size={}B, "
                + "promptLen={}, duration={}, resolution={}",
            principal == null ? null : principal.id(),
            file == null ? null : file.getOriginalFilename(),
            file == null ? null : file.getContentType(),
            file == null ? 0 : file.getSize(),
            prompt == null ? 0 : prompt.length(),
            duration,
            resolution);

        if (principal == null) throw new BusinessException(ErrorCode.UNAUTHORIZED);
        if (file == null || file.isEmpty()) {
            log.warn("[I2V-REQ] 文件为空: userId={}", principal.id());
            throw new BusinessException(ErrorCode.INVALID_PARAM, "file 不能为空");
        }
        if (prompt == null || prompt.isBlank()) {
            log.warn("[I2V-REQ] prompt 为空: userId={}", principal.id());
            throw new BusinessException(ErrorCode.INVALID_PARAM, "prompt 不能为空");
        }

        byte[] fileBytes;
        try {
            fileBytes = file.getBytes();
        } catch (IOException e) {
            log.error("[I2V-REQ] 读取文件失败: userId={}, filename={}, err={}",
                principal.id(), file.getOriginalFilename(), e.getMessage(), e);
            throw new BusinessException(ErrorCode.INVALID_PARAM, "读取文件失败: " + e.getMessage());
        }
        GenerateResponse resp = videoGenerationService.submitImageToVideo(
            principal.id(),
            fileBytes,
            file.getOriginalFilename(),
            file.getContentType(),
            prompt,
            duration,
            resolution
        );
        log.info("[I2V-REQ] 图生视频任务已提交: userId={}, jobId={}, status={}, taskId={}",
            principal.id(), resp.getJobId(), resp.getStatus(), resp.getComfyuiPromptId());
        return resp;
    }
}
