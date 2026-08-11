package com.jurong.aicenter.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jurong.aicenter.client.NewApiClient;
import com.jurong.aicenter.dto.generation.GenerateResponse;
import com.jurong.aicenter.entity.Job;
import com.jurong.aicenter.exception.BusinessException;
import com.jurong.aicenter.exception.ErrorCode;
import com.jurong.aicenter.repository.JobRepository;
import com.jurong.aicenter.service.MediaService;
import com.jurong.aicenter.service.StorageService;
import com.jurong.aicenter.service.VideoGenerationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 视频生成服务实现（图生视频）— 走 NewAPI 中转站，图片直传 multipart。
 *
 * <p>与 Python api_client.py submit_video() 行为一致，不经过 aicoming proxy。
 *
 * <p>job 字段借用约定：
 * <ul>
 *   <li>{@code templateId} = "image-to-video"（标记本服务创建的 job，与 ComfyUI job 区分）</li>
 *   <li>{@code comfyuiPromptId} 存 NewAPI task_id（字段名借用，语义偏移）</li>
 *   <li>{@code inputsSnapshot} 存 JSON：{prompt, duration, resolution, originalFilename, taskId}</li>
 *   <li>{@code resultUrls} 存 MinIO URL 数组 JSON</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VideoGenerationServiceImpl implements VideoGenerationService {

    private final NewApiClient newApiClient;
    private final JobRepository jobRepository;
    private final StorageService storageService;
    private final MediaService mediaService;
    private final ObjectMapper objectMapper;
    private final WebClient.Builder webClientBuilder;

    /** job 标记：本服务创建的图生视频 job */
    private static final String TEMPLATE_ID = "image-to-video";

    /** 视频任务最大存活时间（30 分钟余量） */
    private static final Duration MAX_RUNNING_DURATION = Duration.ofMinutes(30);

    /**
     * 轮询 NewAPI 连续 400 计数（key = jobId）。
     * NewAPI 在任务处理中可能临时返回 400（任务对象尚未完全建好、限流等），
     * 之前一遇 400 就 markFailed 会把本来能成的任务当失败。
     * 这里累计连续 400 次数：达到阈值（默认 5 次 ≈ 10s）才认定任务真的不存在。
     */
    private static final int NOT_FOUND_GRACE_COUNT = 5;
    private final Map<Long, AtomicInteger> notFoundCountMap = new ConcurrentHashMap<>();

    @Override
    public GenerateResponse submitImageToVideo(Long userId,
                                               byte[] fileBytes, String filename, String contentType,
                                               String prompt, int duration, String resolution) {
        log.info("[I2V-SUBMIT] 开始处理: userId={}, filename={}, contentType={}, size={}B, "
                + "promptLen={}, duration={}, resolution={}",
            userId, filename, contentType, fileBytes == null ? 0 : fileBytes.length,
            prompt == null ? 0 : prompt.length(), duration, resolution);

        if (userId == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "用户未登录");
        }
        if (fileBytes == null || fileBytes.length == 0) {
            throw new BusinessException(ErrorCode.INVALID_PARAM, "图片不能为空");
        }
        if (prompt == null || prompt.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_PARAM, "prompt 不能为空");
        }
        final int useDuration = duration > 0 ? duration : 4;
        // 与 Python api_client.py 对齐：分辨率原样传递，不做大小写转换
        final String useResolution = (resolution != null && !resolution.isBlank())
            ? resolution : "480P";

        // 1. 先建 job（PENDING）
        Map<String, Object> inputs = new HashMap<>();
        inputs.put("prompt", prompt);
        inputs.put("duration", useDuration);
        inputs.put("resolution", useResolution);
        inputs.put("originalFilename", filename);

        Job job = new Job();
        job.setUserId(userId);
        job.setTemplateId(TEMPLATE_ID);
        job.setStatus("PENDING");
        job.setInputsSnapshot(toJsonString(inputs));
        job.setCreditsCost(0);
        job.setCreatedAt(LocalDateTime.now());
        jobRepository.insert(job);
        log.info("[I2V-SUBMIT] job 创建: jobId={}, userId={}, size={}B", job.getId(), userId, fileBytes.length);

        // 2. 直接提交视频生成任务到 NewAPI /v1/videos（multipart，图片直传）
        //    与 Python api_client.py submit_video() 行为一致，不经过 aicoming proxy
        String taskId;
        try {
            log.info("[I2V-SUBMIT] jobId={} → 提交视频生成到 NewAPI /v1/videos (multipart, size={}B, duration={}, resolution={})",
                job.getId(), fileBytes.length, useDuration, useResolution);
            taskId = newApiClient.submitVideo(prompt, fileBytes, filename, contentType, useDuration, useResolution);
            log.info("[I2V-SUBMIT] jobId={} ← NewAPI 视频任务已提交: taskId={}", job.getId(), taskId);
        } catch (Exception e) {
            log.error("[I2V-SUBMIT] jobId={} ← NewAPI 视频提交失败: {}", job.getId(), e.getMessage(), e);
            markFailed(job, "submit video failed: " + e.getMessage());
            throw e;
        }

        // 3. 更新 job：存 taskId，标 RUNNING
        inputs.put("taskId", taskId);
        job.setComfyuiPromptId(taskId);  // 借用字段存 NewAPI task_id
        job.setInputsSnapshot(toJsonString(inputs));
        job.setStatus("RUNNING");
        job.setStartedAt(LocalDateTime.now());
        jobRepository.updateById(job);
        log.info("[I2V-SUBMIT] jobId={} → job 标 RUNNING, taskId={}", job.getId(), taskId);

        return new GenerateResponse(job.getId(), job.getStatus(), taskId);
    }

    /**
     * @Scheduled 每 2 秒扫一次 RUNNING 的图生视频 job，调 NewAPI 查状态。
     *
     * <p>只扫 templateId="image-to-video" 的 job，避免与 GenerationService.pollRunningJobs
     * （扫 ComfyUI job）互相干扰。
     */
    @Override
    @Scheduled(fixedDelay = 2000)
    public void pollRunningVideoJobs() {
        List<Job> runningJobs;
        try {
            runningJobs = jobRepository.selectList(
                new LambdaQueryWrapper<Job>()
                    .eq(Job::getStatus, "RUNNING")
                    .eq(Job::getTemplateId, TEMPLATE_ID)
            );
        } catch (Exception e) {
            log.error("[I2V-POLL] 查询 RUNNING job 失败", e);
            return;
        }
        if (runningJobs.isEmpty()) return;

        if (runningJobs.size() >= 5) {
            log.info("[I2V-POLL] 发现 {} 个 RUNNING job", runningJobs.size());
        } else {
            log.debug("[I2V-POLL] 发现 {} 个 RUNNING job", runningJobs.size());
        }
        for (Job job : runningJobs) {
            try {
                processOneVideoJob(job);
            } catch (Exception e) {
                log.error("[I2V-POLL] 处理 job {} 异常: {}", job.getId(), e.getMessage(), e);
            }
        }
    }

    /** 处理单个 RUNNING 的图生视频 job */
    private void processOneVideoJob(Job job) {
        String taskId = job.getComfyuiPromptId();
        if (taskId == null || taskId.isEmpty()) {
            log.error("[I2V-POLL] job {} 缺 taskId, 标 FAILED", job.getId());
            markFailed(job, "missing taskId");
            return;
        }

        // 超时检测
        if (job.getStartedAt() != null
            && Duration.between(job.getStartedAt(), LocalDateTime.now()).compareTo(MAX_RUNNING_DURATION) > 0) {
            log.error("[I2V-POLL] job {} 超时 (RUNNING > {}min), startedAt={}",
                job.getId(), MAX_RUNNING_DURATION.toMinutes(), job.getStartedAt());
            markFailed(job, "timeout: RUNNING > " + MAX_RUNNING_DURATION.toMinutes() + "min");
            return;
        }

        // 查 NewAPI 视频任务状态（单次查询，不阻塞）
        JsonNode result;
        try {
            result = newApiClient.pollVideo(taskId);
        } catch (BusinessException e) {
            // 2026-08-09 fix: 400 (任务不存在)时**不要**立即标 FAILED,NewAPI 在处理中可能临时 400;
            // 累计连续 400 次数,达到 NOT_FOUND_GRACE_COUNT 才认定真的不存在
            String errMsg = e.getMessage() == null ? "" : e.getMessage();
            boolean taskGone = errMsg.contains("400") || errMsg.toLowerCase().contains("not found") || errMsg.toLowerCase().contains("task does not exist");
            if (taskGone) {
                int n = notFoundCountMap.computeIfAbsent(job.getId(), k -> new AtomicInteger(0)).incrementAndGet();
                if (n >= NOT_FOUND_GRACE_COUNT) {
                    log.error("[I2V-POLL] job {} NewAPI 任务连续 {} 次 400 (taskId={}), 标 FAILED: {}",
                        job.getId(), n, taskId, errMsg);
                    notFoundCountMap.remove(job.getId());
                    markFailed(job, "NewAPI task not found (after " + n + " retries): " + errMsg);
                } else {
                    log.warn("[I2V-POLL] job {} NewAPI 任务 400 ({}/{}), 继续重试: taskId={}, err={}",
                        job.getId(), n, NOT_FOUND_GRACE_COUNT, taskId, errMsg);
                }
                return;
            }
            // 其他错误(503/网络问题等)继续重试
            log.warn("[I2V-POLL] job {} 查询 NewAPI 失败 (下次重试): taskId={}, err={}",
                job.getId(), taskId, errMsg);
            return;
        }
        // 成功响应 → 清掉 400 计数
        notFoundCountMap.remove(job.getId());
        if (result == null) {
            log.warn("[I2V-POLL] job {} 查询 NewAPI 返回 null: taskId={}", job.getId(), taskId);
            return;
        }
        String status = result.path("status").asText("unknown").toLowerCase();

        // 关键日志：打印 NewAPI poll 原始响应（截断到 2000 字符避免日志过大）
        // 这是排查问题最关键的日志，能看到 aicoming 实际返回什么
        log.info("[I2V-POLL] job {} 状态: taskId={}, status={}, raw={}",
            job.getId(), taskId, status, truncateForLog(result.toString(), 2000));

        if ("completed".equals(status) || "succeeded".equals(status) || "success".equals(status)) {
            log.info("[I2V-POLL] job {} → 视频已完成，开始下载", job.getId());
            handleCompleted(job, result);
        } else if ("failed".equals(status) || "error".equals(status) || "cancelled".equals(status)) {
            log.error("[I2V-POLL] job {} → NewAPI 任务失败: {}", job.getId(), result);
            markFailed(job, "NewAPI task failed: " + result);
        }
        // in_progress / unknown → 跳过，下次再扫
    }

    /** 视频任务完成：抠 URL → 下载 → 上传 MinIO → 标 COMPLETED */
    private void handleCompleted(Job job, JsonNode result) {
        String videoUrl = newApiClient.extractVideoUrl(result);
        if (videoUrl == null || videoUrl.isBlank()) {
            log.error("[I2V-DONE] job {} ← 响应中未找到 video URL: {}", job.getId(), result);
            markFailed(job, "NewAPI 响应中未找到 video URL: " + result);
            return;
        }
        log.info("[I2V-DONE] job {} → 视频URL: {}", job.getId(), videoUrl);

        String filename = "jurong_i2v_" + job.getId() + ".mp4";
        byte[] bytes;
        try {
            log.info("[I2V-DONE] job {} → 下载视频字节: url={}", job.getId(), videoUrl);
            bytes = downloadBytes(videoUrl);
        } catch (Exception e) {
            log.error("[I2V-DONE] job {} ← 下载视频失败: {}", job.getId(), e.getMessage(), e);
            markFailed(job, "download video failed: " + e.getMessage());
            return;
        }
        if (bytes == null || bytes.length == 0) {
            log.error("[I2V-DONE] job {} ← 下载的视频字节为空", job.getId());
            markFailed(job, "downloaded video is empty");
            return;
        }
        log.info("[I2V-DONE] job {} ← 视频已下载: {}B", job.getId(), bytes.length);

        try (InputStream is = new ByteArrayInputStream(bytes)) {
            // 直接调 uploadAiMedia 拿到 UploadResult(含 objectKey)，
            // 确保 media_assets.object_key 与 MinIO 实际路径一致
            String ext = "mp4";
            String contentType = "video/mp4";
            StorageService.UploadResult up = storageService.uploadAiMedia(
                job.getUserId(), ext, is, contentType);
            String objectKey = up.objectKey();
            String minioUrl = up.url();
            log.info("[I2V-DONE] job {} ← 已上传到 MinIO: objectKey={}, url={}",
                job.getId(), objectKey, minioUrl);

            // 写入 media_assets 表（AI 生成结果库），objectKey 与 MinIO 实际路径一致
            try {
                mediaService.recordAiGenerated(
                    job.getUserId(), "video", filename, contentType,
                    (long) bytes.length, objectKey, "video", String.valueOf(job.getId()));
                log.info("[I2V-DONE] job {} ← 已写入 media_assets 表: objectKey={}", job.getId(), objectKey);
            } catch (Exception e) {
                // 入库失败不阻塞任务完成，只记日志
                log.warn("[I2V-DONE] job {} ← recordAiGenerated 失败: {}", job.getId(), e.getMessage());
            }

            markCompleted(job, List.of(minioUrl));
            log.info("[I2V-DONE] job {} ← 任务完成", job.getId());
        } catch (Exception e) {
            log.error("[I2V-DONE] job {} ← 上传 MinIO 失败: {}", job.getId(), e.getMessage(), e);
            markFailed(job, "upload to MinIO failed: " + e.getMessage());
        }
    }

    /** 简单 GET 下载视频字节（与 VideoSyncServiceImpl 风格一致） */
    private byte[] downloadBytes(String url) {
        return webClientBuilder.build()
            .get()
            .uri(url)
            .header(HttpHeaders.ACCEPT, MediaType.APPLICATION_OCTET_STREAM_VALUE)
            .retrieve()
            .bodyToMono(byte[].class)
            .timeout(Duration.ofSeconds(300))
            .block();
    }

    private void markCompleted(Job job, List<String> resultUrls) {
        job.setStatus("COMPLETED");
        job.setResultUrls(toJsonString(resultUrls));
        job.setCompletedAt(LocalDateTime.now());
        if (job.getStartedAt() != null) {
            job.setDurationMs((int) Duration.between(job.getStartedAt(), job.getCompletedAt()).toMillis());
        }
        jobRepository.updateById(job);
        log.info("[I2V-DONE] job {} → 标 COMPLETED, resultUrls={}, durationMs={}",
            job.getId(), resultUrls, job.getDurationMs());
    }

    private void markFailed(Job job, String errorMessage) {
        job.setStatus("FAILED");
        job.setErrorMessage(errorMessage);
        job.setCompletedAt(LocalDateTime.now());
        if (job.getStartedAt() != null) {
            job.setDurationMs((int) Duration.between(job.getStartedAt(), job.getCompletedAt()).toMillis());
        }
        jobRepository.updateById(job);
        log.warn("[I2V-DONE] job {} → 标 FAILED, err={}, durationMs={}",
            job.getId(), errorMessage, job.getDurationMs());
    }

    private String toJsonString(Object obj) {
        if (obj == null) return null;
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            log.warn("toJsonString failed: {}", e.getMessage());
            return null;
        }
    }

    /** 把超长 JSON 字符串截断到指定长度，方便日志查看（避免 NewAPI 响应把日志撑爆） */
    private String truncateForLog(String s, int maxLen) {
        if (s == null) return "null";
        if (s.length() <= maxLen) return s;
        return s.substring(0, maxLen) + "...(truncated, totalLen=" + s.length() + ")";
    }
}
