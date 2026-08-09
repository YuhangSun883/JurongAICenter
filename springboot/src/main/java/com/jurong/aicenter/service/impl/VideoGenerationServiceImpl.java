package com.jurong.aicenter.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jurong.aicenter.client.AicomingAssetsClient;
import com.jurong.aicenter.client.NewApiClient;
import com.jurong.aicenter.dto.generation.GenerateResponse;
import com.jurong.aicenter.entity.Job;
import com.jurong.aicenter.exception.BusinessException;
import com.jurong.aicenter.exception.ErrorCode;
import com.jurong.aicenter.repository.JobRepository;
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

/**
 * 视频生成服务实现（图生视频）— 走 NewAPI 中转站，绕过 ComfyUI。
 *
 * <p>严格按 Assets-API 参考手册 §5 端到端流程实现。
 *
 * <p>job 字段借用约定：
 * <ul>
 *   <li>{@code templateId} = "image-to-video"（标记本服务创建的 job，与 ComfyUI job 区分）</li>
 *   <li>{@code comfyuiPromptId} 存 NewAPI task_id（字段名借用，语义偏移）</li>
 *   <li>{@code inputsSnapshot} 存 JSON：{prompt, duration, resolution, originalFilename, assetId, assetUrl, taskId}</li>
 *   <li>{@code resultUrls} 存 MinIO URL 数组 JSON</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VideoGenerationServiceImpl implements VideoGenerationService {

    private final AicomingAssetsClient assetsClient;
    private final NewApiClient newApiClient;
    private final JobRepository jobRepository;
    private final StorageService storageService;
    private final ObjectMapper objectMapper;
    private final WebClient.Builder webClientBuilder;

    /** job 标记：本服务创建的图生视频 job */
    private static final String TEMPLATE_ID = "image-to-video";

    /** asset 轮询参数（手册 §4.2 推荐：3 秒一次，最多 90 秒） */
    private static final int ASSET_POLL_MAX_WAIT_SEC = 90;
    private static final int ASSET_POLL_INTERVAL_SEC = 3;

    /** 视频任务最大存活时间（手册/README 提到 aicoming 视频生成 5+ 分钟，给 30 分钟余量） */
    private static final Duration MAX_RUNNING_DURATION = Duration.ofMinutes(30);

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
        // aicoming 只接受小写 resolution（480p/720p/1080p/4k），大写会报 invalid_resolution
        final String useResolution = (resolution != null && !resolution.isBlank())
            ? resolution.toLowerCase() : "480p";

        // 1. 先建 job（PENDING），把输入快照存好（assetId/assetUrl/taskId 后续补）
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

        // 2. 上传图片到 proxy 8080 拿 asset_url
        String assetName = "i2v-" + job.getId();
        JsonNode assetData;
        try {
            log.info("[I2V-SUBMIT] jobId={} → 上传图片到 aicoming-proxy /v1/assets (name={})", job.getId(), assetName);
            assetData = assetsClient.uploadAssetByMultipart(fileBytes, filename, contentType, assetName);
            log.info("[I2V-SUBMIT] jobId={} ← asset 上传成功: assetId={}, assetUrl={}, status={}",
                job.getId(),
                assetData.path("id").asText(),
                assetData.path("asset_url").asText(),
                assetData.path("status").asText());
        } catch (Exception e) {
            log.error("[I2V-SUBMIT] jobId={} ← asset 上传失败: {}", job.getId(), e.getMessage(), e);
            markFailed(job, "upload asset failed: " + e.getMessage());
            throw e;
        }
        String assetId = assetData.path("id").asText("");
        String assetUrl = assetData.path("asset_url").asText("");
        if (assetId.isEmpty() || assetUrl.isEmpty()) {
            log.error("[I2V-SUBMIT] jobId={} ← asset 响应缺 id/asset_url: {}", job.getId(), assetData);
            markFailed(job, "asset 响应缺 id/asset_url: " + assetData);
            throw new BusinessException(ErrorCode.ASSET_UPLOAD_FAILED, "asset 响应缺 id/asset_url");
        }

        // 3. 轮询 asset 就绪
        try {
            log.info("[I2V-SUBMIT] jobId={} → 轮询 asset 就绪 (maxWaitSec={}, intervalSec={})",
                job.getId(), ASSET_POLL_MAX_WAIT_SEC, ASSET_POLL_INTERVAL_SEC);
            assetsClient.pollUntilActive(assetId, ASSET_POLL_MAX_WAIT_SEC, ASSET_POLL_INTERVAL_SEC);
            log.info("[I2V-SUBMIT] jobId={} ← asset 已就绪 (active)", job.getId());
        } catch (Exception e) {
            log.error("[I2V-SUBMIT] jobId={} ← asset 未就绪: {}", job.getId(), e.getMessage(), e);
            markFailed(job, "asset not active: " + e.getMessage());
            assetsClient.deleteAsset(assetId);  // best-effort 清理
            throw e;
        }

        // 4. 提交视频生成任务（NewAPI 3000，JSON body，image_urls 引用 asset_url）
        String taskId;
        try {
            log.info("[I2V-SUBMIT] jobId={} → 提交视频生成到 NewAPI /v1/videos (assetUrl={}, duration={}, resolution={})",
                job.getId(), assetUrl, useDuration, useResolution);
            taskId = newApiClient.submitVideoWithAsset(prompt, assetUrl, null, useDuration, useResolution);
            log.info("[I2V-SUBMIT] jobId={} ← NewAPI 视频任务已提交: taskId={}", job.getId(), taskId);
        } catch (Exception e) {
            log.error("[I2V-SUBMIT] jobId={} ← NewAPI 视频提交失败: {}", job.getId(), e.getMessage(), e);
            markFailed(job, "submit video failed: " + e.getMessage());
            assetsClient.deleteAsset(assetId);  // best-effort 清理
            throw e;
        }

        // 5. 更新 job：存 taskId + assetId，标 RUNNING
        inputs.put("assetId", assetId);
        inputs.put("assetUrl", assetUrl);
        inputs.put("taskId", taskId);
        job.setComfyuiPromptId(taskId);  // 借用字段存 NewAPI task_id
        job.setInputsSnapshot(toJsonString(inputs));
        job.setStatus("RUNNING");
        job.setStartedAt(LocalDateTime.now());
        jobRepository.updateById(job);
        log.info("[I2V-SUBMIT] jobId={} → job 标 RUNNING, taskId={}, assetId={}", job.getId(), taskId, assetId);

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
            cleanupAsset(job);
            return;
        }

        // 超时检测
        if (job.getStartedAt() != null
            && Duration.between(job.getStartedAt(), LocalDateTime.now()).compareTo(MAX_RUNNING_DURATION) > 0) {
            log.error("[I2V-POLL] job {} 超时 (RUNNING > {}min), startedAt={}",
                job.getId(), MAX_RUNNING_DURATION.toMinutes(), job.getStartedAt());
            markFailed(job, "timeout: RUNNING > " + MAX_RUNNING_DURATION.toMinutes() + "min");
            cleanupAsset(job);
            return;
        }

        // 查 NewAPI 视频任务状态（单次查询，不阻塞）
        JsonNode result;
        try {
            result = newApiClient.pollVideo(taskId);
        } catch (BusinessException e) {
            // NewAPI 暂时不可达，下次重试（不打 error，避免刷屏）
            log.warn("[I2V-POLL] job {} 查询 NewAPI 失败 (下次重试): taskId={}, err={}",
                job.getId(), taskId, e.getMessage());
            return;
        }
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
            cleanupAsset(job);
        }
        // in_progress / unknown → 跳过，下次再扫
    }

    /** 视频任务完成：抠 URL → 下载 → 上传 MinIO → 标 COMPLETED → 清理 asset */
    private void handleCompleted(Job job, JsonNode result) {
        String videoUrl = newApiClient.extractVideoUrl(result);
        if (videoUrl == null || videoUrl.isBlank()) {
            log.error("[I2V-DONE] job {} ← 响应中未找到 video URL: {}", job.getId(), result);
            markFailed(job, "NewAPI 响应中未找到 video URL: " + result);
            cleanupAsset(job);
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
            cleanupAsset(job);
            return;
        }
        if (bytes == null || bytes.length == 0) {
            log.error("[I2V-DONE] job {} ← 下载的视频字节为空", job.getId());
            markFailed(job, "downloaded video is empty");
            cleanupAsset(job);
            return;
        }
        log.info("[I2V-DONE] job {} ← 视频已下载: {}B", job.getId(), bytes.length);

        try (InputStream is = new ByteArrayInputStream(bytes)) {
            String minioUrl = storageService.uploadFile(
                job.getUserId(), job.getId(), filename, is, "video/mp4");
            log.info("[I2V-DONE] job {} ← 已上传到 MinIO: {}", job.getId(), minioUrl);
            markCompleted(job, List.of(minioUrl));
            log.info("[I2V-DONE] job {} ← 任务完成", job.getId());
        } catch (Exception e) {
            log.error("[I2V-DONE] job {} ← 上传 MinIO 失败: {}", job.getId(), e.getMessage(), e);
            markFailed(job, "upload to MinIO failed: " + e.getMessage());
        } finally {
            cleanupAsset(job);
        }
    }

    /** best-effort 清理 asset（手册 §5 末尾步骤） */
    private void cleanupAsset(Job job) {
        String assetId = extractFromInputs(job, "assetId");
        if (assetId == null || assetId.isEmpty()) {
            log.debug("[I2V-CLEAN] job {} 无 assetId，跳过清理", job.getId());
            return;
        }
        log.info("[I2V-CLEAN] job {} → 清理 asset: {}", job.getId(), assetId);
        assetsClient.deleteAsset(assetId);
    }

    /** 从 inputsSnapshot JSON 里抠一个字段 */
    private String extractFromInputs(Job job, String key) {
        if (job.getInputsSnapshot() == null || job.getInputsSnapshot().isBlank()) return null;
        try {
            JsonNode node = objectMapper.readTree(job.getInputsSnapshot());
            JsonNode val = node.get(key);
            return val == null ? null : val.asText(null);
        } catch (Exception e) {
            log.warn("extractFromInputs({},{}) failed: {}", job.getId(), key, e.getMessage());
            return null;
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
