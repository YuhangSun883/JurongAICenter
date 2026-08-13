package com.jurong.aicenter.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.jurong.aicenter.dto.generation.GenerateResponse;
import com.jurong.aicenter.dto.job.JobResponse;
import com.jurong.aicenter.dto.video.VideoOptions;
import com.jurong.aicenter.entity.Job;
import com.jurong.aicenter.exception.BusinessException;
import com.jurong.aicenter.exception.ErrorCode;
import com.jurong.aicenter.repository.JobRepository;
import com.jurong.aicenter.repository.MediaAssetRepository;
import com.jurong.aicenter.security.JwtAuthenticationFilter.AuthenticatedUser;
import com.jurong.aicenter.service.VideoGenerationService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 视频生成端点 — 全部走 NewAPI 中转站（2026-08-11 重构：移除 ComfyUI 流程）。
 *
 * <p>端点：
 * <pre>
 *   POST /api/videos/text-to-video          (JSON body, 文字生成视频 → NewAPI)
 *   POST /api/videos/image-to-video         (multipart/form-data, 图片生成视频 → NewAPI)
 *   POST /api/videos/multi-image-to-video   (multipart/form-data, 多图生成视频 → NewAPI)
 *   GET  /api/videos                        (列出本用户的视频生成任务)
 * </pre>
 */
@Slf4j
@RestController
@RequestMapping("/api/videos")
@RequiredArgsConstructor
public class VideoGenerationController {

    private final VideoGenerationService videoGenerationService;
    private final JobRepository jobRepository;
    private final ObjectMapper objectMapper;
    private final MediaAssetRepository mediaAssetRepository;

    /**
     * 文字生成视频 — NewAPI 中转站。
     *
     * <p>2026-08-11：移除 ComfyUI 流程，统一走 NewAPI。
     * 请求体：{script, model, aspectRatio, resolution, duration, audioMode, seed?}
     */
    @PostMapping("/text-to-video")
    public GenerateResponse textToVideo(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @RequestBody Map<String, Object> request) {
        if (principal == null) throw new BusinessException(ErrorCode.UNAUTHORIZED);

        String script = (String) request.getOrDefault("script", "");
        if (script == null || script.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_PARAM, "script 不能为空");
        }

        String frontModel = (String) request.getOrDefault("model", "Seedance-2.0-VIP");
        String aspectRatio = (String) request.getOrDefault("aspectRatio", "16:9");
        // aicoming 只接受小写 resolution（480p/720p/1080p/4k）
        String resolution = (String) request.getOrDefault("resolution", "720p");
        int duration = request.get("duration") instanceof Number n ? n.intValue() : 4;
        String audioMode = (String) request.getOrDefault("audioMode", "mute");
        Long seed = request.get("seed") instanceof Number ns ? ns.longValue() : 0L;

        // 映射前端 videoModel 枚举到后端 model ID
        // 前端三档：Seedance-2.0-VIP / Seedance-2.0-Fast-VIP / Seedance-2.0-Mini-VIP
        // 后端 NewAPI 当前只支持 doubao-seedance-2.0 基础模型（NewAPI 文档 §2）
        String backendModel = mapFrontendModel(frontModel);

        // 组装 VideoOptions
        VideoOptions options = VideoOptions.builder()
            .duration(mapToValidDuration(duration))
            .resolution(resolution)
            .ratio(aspectRatio)
            .generateAudio(!"mute".equalsIgnoreCase(audioMode))
            .watermark(false)
            .returnLastFrame(true)
            .seed(seed == null ? 0L : seed)
            .model(backendModel)
            .build();

        log.info("[T2V-REQ] userId={}, frontModel={}, backendModel={}, duration={}, resolution={}, "
                + "ratio={}, audioMode={}, seed={}",
            principal.id(), frontModel, backendModel, options.getDuration(), options.getResolution(),
            options.getRatio(), audioMode, options.getSeed());

        GenerateResponse resp = videoGenerationService.submitTextToVideo(principal.id(), script, options);
        log.info("[T2V-REQ] 文生视频任务已提交: userId={}, jobId={}, status={}, taskId={}",
            principal.id(), resp.getJobId(), resp.getStatus(), resp.getComfyuiPromptId());
        return resp;
    }

    /**
     * 图生视频：支持两种模式（按《聚融中转站接口手册 v2.1》§7）
     *
     * <p>模式 A（推荐，对齐文档）：传 {@code imageUrl} 字符串（http(s):// 或 asset://aic_xxx），
     *    后端直接把 URL 转发给 NewAPI 的 {@code image_urls} 字段。
     * <p>模式 B（兼容旧版）：传 {@code file} multipart 文件，后端用 multipart 直传 NewAPI。
     *
     * <p>字段优先级：imageUrl > file。当传了 imageUrl 时，file 字段可不传。
     */
    @PostMapping("/image-to-video")
    public GenerateResponse imageToVideo(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @RequestParam(value = "file", required = false) MultipartFile file,
            @RequestParam(value = "imageUrl", required = false) String imageUrl,
            @RequestParam("prompt") String prompt,
            @RequestParam(value = "duration", defaultValue = "4") int duration,
            @RequestParam(value = "resolution", defaultValue = "480p") String resolution) {
        log.info("[I2V-REQ] 收到图生视频请求: userId={}, hasImageUrl={}, hasFile={}, "
                + "imageUrl={}, filename={}, size={}B, promptLen={}, duration={}, resolution={}",
            principal == null ? null : principal.id(),
            imageUrl != null && !imageUrl.isBlank(),
            file != null && !file.isEmpty(),
            imageUrl,
            file == null ? null : file.getOriginalFilename(),
            file == null ? 0 : file.getSize(),
            prompt == null ? 0 : prompt.length(),
            duration, resolution);

        if (principal == null) throw new BusinessException(ErrorCode.UNAUTHORIZED);
        if (prompt == null || prompt.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_PARAM, "prompt 不能为空");
        }

        boolean hasUrl = imageUrl != null && !imageUrl.isBlank();
        boolean hasFile = file != null && !file.isEmpty();
        if (!hasUrl && !hasFile) {
            throw new BusinessException(ErrorCode.INVALID_PARAM,
                "imageUrl 和 file 至少需要传一个（推荐传 imageUrl）");
        }

        GenerateResponse resp;
        if (hasUrl) {
            // 模式 A：用 URL 转发（推荐，绕过 ORB/CORS + 支持 asset://）
            String url = imageUrl.trim();
            // 校验 URL 形式
            if (!(url.startsWith("http://") || url.startsWith("https://") || url.startsWith("asset://"))) {
                throw new BusinessException(ErrorCode.INVALID_PARAM,
                    "imageUrl 必须以 http:// / https:// / asset:// 开头");
            }
            log.info("[I2V-REQ] 模式 A（URL 转发）: imageUrl={}", url);
            resp = videoGenerationService.submitImageToVideoByUrl(
                principal.id(), url, prompt, duration, resolution);
        } else {
            // 模式 B：multipart 直传（兼容旧版）
            byte[] fileBytes;
            try {
                fileBytes = file.getBytes();
            } catch (IOException e) {
                log.error("[I2V-REQ] 读取文件失败: userId={}, filename={}, err={}",
                    principal.id(), file.getOriginalFilename(), e.getMessage(), e);
                throw new BusinessException(ErrorCode.INVALID_PARAM, "读取文件失败: " + e.getMessage());
            }
            log.info("[I2V-REQ] 模式 B（multipart 上传）: filename={}", file.getOriginalFilename());
            resp = videoGenerationService.submitImageToVideo(
                principal.id(), fileBytes, file.getOriginalFilename(), file.getContentType(),
                prompt, duration, resolution);
        }

        log.info("[I2V-REQ] 图生视频任务已提交: userId={}, jobId={}, status={}, taskId={}",
            principal.id(), resp.getJobId(), resp.getStatus(), resp.getComfyuiPromptId());
        return resp;
    }

    /**
     * 多图生视频：上传 1-4 张图片 + 提示词 → NewAPI 中转站 → 异步生成视频。
     *
     * <p>2026-08-11 新增。
     * 请求字段：file1, file2?, file3?, file4?, prompt, duration, resolution, ratio?
     */
    @PostMapping("/multi-image-to-video")
    public GenerateResponse multiImageToVideo(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @RequestParam("prompt") String prompt,
            @RequestParam(value = "duration", defaultValue = "4") int duration,
            @RequestParam(value = "resolution", defaultValue = "480p") String resolution,
            @RequestParam(value = "ratio", required = false) String ratio,
            @RequestParam(value = "file1", required = false) MultipartFile file1,
            @RequestParam(value = "file2", required = false) MultipartFile file2,
            @RequestParam(value = "file3", required = false) MultipartFile file3,
            @RequestParam(value = "file4", required = false) MultipartFile file4) {
        if (principal == null) throw new BusinessException(ErrorCode.UNAUTHORIZED);
        if (prompt == null || prompt.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_PARAM, "prompt 不能为空");
        }

        // 收集所有非空图片
        MultipartFile[] files = new MultipartFile[]{file1, file2, file3, file4};
        List<byte[]> imageBytesList = new ArrayList<>();
        for (MultipartFile f : files) {
            if (f != null && !f.isEmpty()) {
                try {
                    imageBytesList.add(f.getBytes());
                } catch (IOException e) {
                    log.error("[MI2V-REQ] 读取文件失败: filename={}, err={}",
                        f.getOriginalFilename(), e.getMessage(), e);
                    throw new BusinessException(ErrorCode.INVALID_PARAM, "读取文件失败: " + e.getMessage());
                }
            }
        }
        if (imageBytesList.isEmpty()) {
            throw new BusinessException(ErrorCode.INVALID_PARAM, "至少需要 1 张参考图");
        }

        VideoOptions options = VideoOptions.builder()
            .duration(mapToValidDuration(duration))
            .resolution(resolution)
            .ratio(ratio)
            .build();

        log.info("[MI2V-REQ] userId={}, images={}, duration={}, resolution={}, ratio={}, promptLen={}",
            principal.id(), imageBytesList.size(), options.getDuration(), options.getResolution(),
            options.getRatio(), prompt.length());

        GenerateResponse resp = videoGenerationService.submitMultiImageToVideo(
            principal.id(), prompt, imageBytesList, options);
        log.info("[MI2V-REQ] 多图生视频任务已提交: userId={}, jobId={}, status={}, taskId={}",
            principal.id(), resp.getJobId(), resp.getStatus(), resp.getComfyuiPromptId());
        return resp;
    }

    @GetMapping
    public Map<String, Object> listVideos(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        if (principal == null) throw new BusinessException(ErrorCode.UNAUTHORIZED);

        // 列出本用户的所有视频生成 job（text-to-video / image-to-video / multi-image-to-video）
        LambdaQueryWrapper<Job> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Job::getUserId, principal.id())
               .in(Job::getTemplateId, List.of(
                   VideoGenerationService.TEMPLATE_TEXT_TO_VIDEO,
                   VideoGenerationService.TEMPLATE_IMAGE_TO_VIDEO,
                   VideoGenerationService.TEMPLATE_MULTI_IMAGE_TO_VIDEO
               ))
               .orderByDesc(Job::getCreatedAt);

        Page<Job> mpPage = jobRepository.selectPage(
            new Page<>(page, Math.min(pageSize, 100)), wrapper);

        List<JobResponse> items = mpPage.getRecords().stream().map(job -> {
            List<String> resultUrls = null;
            if (job.getResultUrls() != null && !job.getResultUrls().isBlank()) {
                try {
                    resultUrls = objectMapper.readValue(job.getResultUrls(), new TypeReference<>() {});
                } catch (Exception ignored) {}
            }
            Long mediaAssetId = null;
            if (job.getStatus() != null && "COMPLETED".equalsIgnoreCase(job.getStatus())) {
                try {
                    com.jurong.aicenter.entity.MediaAsset asset = mediaAssetRepository.selectOne(
                        new LambdaQueryWrapper<com.jurong.aicenter.entity.MediaAsset>()
                            .eq(com.jurong.aicenter.entity.MediaAsset::getUserId, principal.id())
                            .eq(com.jurong.aicenter.entity.MediaAsset::getSourceTaskId, String.valueOf(job.getId()))
                            .last("LIMIT 1")
                    );
                    if (asset != null) mediaAssetId = asset.getId();
                } catch (Exception ignored) {}
            }
            return new JobResponse(
                job.getId(), job.getWorkflowId(), job.getTemplateId(),
                job.getStatus(), job.getCreditsCost(), job.getDurationMs(),
                resultUrls, job.getErrorMessage(),
                job.getCreatedAt(), job.getCompletedAt(), mediaAssetId
            );
        }).toList();

        return Map.of("items", items, "total", mpPage.getTotal());
    }

    /**
     * 手动重试/补刀单个视频任务。
     *
     * <p>用途：任务被兜底逻辑误标 FAILED，但 NewAPI 实际已生成视频时，
     * 用户点这个端点会重新查 NewAPI 并自动下载入库。
     *
     * <p>行为：
     *   - 任务必须是当前用户的
     *   - 任务必须有 NewAPI taskId
     *   - NewAPI 必须返回 completed + URL
     *   - 成功 → 任务标 COMPLETED，返回 { recovered: true }
     *   - 失败 → 返回 { recovered: false, reason: "..." }
     */
    @PostMapping("/{id}/retry")
    public Map<String, Object> retryVideo(@AuthenticationPrincipal AuthenticatedUser principal,
                                          @PathVariable("id") Long jobId) {
        if (principal == null) throw new BusinessException(ErrorCode.UNAUTHORIZED);

        // 校验任务存在且属于当前用户
        Job job = jobRepository.selectById(jobId);
        if (job == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "任务不存在: " + jobId);
        }
        if (!job.getUserId().equals(principal.id())) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "无权操作此任务");
        }

        // 区分两种调用方：
        //   - 手动补刀（前端用户点击按钮）：调用时 incrementRetryCounter，超过 5 次只打 warn
        //   - 自动补刀（前端 useTaskPolling 每 2 秒轮询调用）：静默，只在每次"自动调用时 NewAPI 状态有变化"才打 info
        boolean isAutoRetry = isAutoRetryRequest();
        int retryCount = incrementRetryCounter(jobId, isAutoRetry);

        if (isAutoRetry) {
            // 自动补刀：只在 retryCount 每累计 10 次（或状态变化）时打一条 info，避免日志刷屏
            String prevStatus = lastSeenStatuses.put(jobId, job.getStatus());
            if (retryCount == 1 || retryCount % 10 == 0 || !job.getStatus().equals(prevStatus)) {
                log.info("[VIDEO-RETRY-AUTO] userId={}, jobId={}, attempt={}, currentStatus={}",
                    principal.id(), jobId, retryCount, job.getStatus());
            }
        } else {
            log.info("[VIDEO-RETRY-MANUAL] userId={}, jobId={}, currentStatus={}",
                principal.id(), jobId, job.getStatus());
        }

        boolean recovered = videoGenerationService.retryJobById(jobId);

        // 重查任务返回最新状态
        Job fresh = jobRepository.selectById(jobId);
        Map<String, Object> result = new HashMap<>();
        result.put("jobId", jobId);
        result.put("recovered", recovered);
        result.put("currentStatus", fresh != null ? fresh.getStatus() : null);
        if (!recovered) {
            result.put("reason", "NewAPI 上任务尚未完成或无法获取 URL");
        }
        return result;
    }

    // ============================================================
    // 自动/手动补刀区分 + 节流日志
    // ============================================================

    /**
     * 每个 jobId 的自动补刀调用次数（用于节流日志和未来限流）
     * key=jobId, value=调用次数
     */
    private final java.util.concurrent.ConcurrentHashMap<Long, java.util.concurrent.atomic.AtomicInteger>
        autoRetryCounters = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * 每个 jobId 上一次自动补刀时看到的 currentStatus（用于检测状态变化）
     */
    private final java.util.concurrent.ConcurrentHashMap<Long, String>
        lastSeenStatuses = new java.util.concurrent.ConcurrentHashMap<>();

    /** 自动补刀请求的标识 header（前端 useTaskPolling 自动设置） */
    private static final String HDR_AUTO_RETRY = "X-Auto-Retry";

    /** 检测当前请求是否来自前端自动补刀机制（每 2 秒一次的那个） */
    private boolean isAutoRetryRequest() {
        try {
            jakarta.servlet.http.HttpServletRequest req =
                ((org.springframework.web.context.request.ServletRequestAttributes)
                    org.springframework.web.context.request.RequestContextHolder.currentRequestAttributes())
                    .getRequest();
            return "true".equalsIgnoreCase(req.getHeader(HDR_AUTO_RETRY));
        } catch (Exception e) {
            return false;
        }
    }

    /** 增加调用计数；返回新的累计次数 */
    private int incrementRetryCounter(Long jobId, boolean isAuto) {
        if (!isAuto) {
            // 手动补刀不计数，避免污染
            return 0;
        }
        return autoRetryCounters
            .computeIfAbsent(jobId, k -> new java.util.concurrent.atomic.AtomicInteger(0))
            .incrementAndGet();
    }

    /**
     * 上游主动失败时调用：清理该 jobId 的节流缓存，停止后续自动重试
     */
    private void markAbandoned(Long jobId) {
        autoRetryCounters.remove(jobId);
        lastSeenStatuses.remove(jobId);
    }

    // ========== 辅助方法 ==========

    /**
     * 映射前端视频模型枚举到后端 NewAPI 实际可用的 model ID。
     *
     * <p>前端三档：Seedance-2.0-VIP / Seedance-2.0-Fast-VIP / Seedance-2.0-Mini-VIP
     * <br>NewAPI 当前只支持 doubao-seedance-2.0 基础模型，三档暂时都映射到同一档，
     * 后续 NewAPI 上线多档再细化。映射过程记录在日志中。
     */
    private String mapFrontendModel(String frontModel) {
        if (frontModel == null || frontModel.isBlank()) {
            return "doubao-seedance-2.0";
        }
        // 当前阶段所有前端模型都映射到基础 doubao-seedance-2.0（NewAPI 实际可用）
        // 注释保留扩展点：等 NewAPI 上线 -fast / -mini 时在此 switch
        return switch (frontModel) {
            case "Seedance-2.0-Fast-VIP", "Seedance-2.0-Mini-VIP", "Seedance-2.0-VIP" -> "doubao-seedance-2.0";
            default -> "doubao-seedance-2.0";
        };
    }

    /** 将前端 duration (4-30) 映射到 NewAPI 支持的合法值 [4, 8, 12] */
    private static int mapToValidDuration(int duration) {
        int[] valid = {4, 8, 12};
        int nearest = valid[0];
        int minDist = Math.abs(duration - valid[0]);
        for (int i = 1; i < valid.length; i++) {
            int dist = Math.abs(duration - valid[i]);
            if (dist < minDist) {
                minDist = dist;
                nearest = valid[i];
            }
        }
        return nearest;
    }
}
