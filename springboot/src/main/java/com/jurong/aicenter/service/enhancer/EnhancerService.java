package com.jurong.aicenter.service.enhancer;

import com.fasterxml.jackson.databind.JsonNode;
import com.jurong.aicenter.client.NewApiClient;
import com.jurong.aicenter.dto.enhancer.EnhancerJobResponse;
import com.jurong.aicenter.dto.enhancer.EnhancerSubmitRequest;
import com.jurong.aicenter.dto.enhancer.EnhancerSubmitResponse;
import com.jurong.aicenter.exception.BusinessException;
import com.jurong.aicenter.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 2026-08-15 新增:画质增强服务
 *
 * <p>工作流程:
 * <ol>
 *   <li>前端 POST /api/image-enhancer/submit → 这里构造 v2v 协议 body 调 NewApiClient.submitEnhanceVideo</li>
 *   <li>NewAPI 异步生成,前端定时 GET /api/image-enhancer/jobs/{taskId} 轮询</li>
 *   <li>本服务调 NewApiClient.pollVideo 查状态,提取 outputUrl 后回给前端</li>
 * </ol>
 *
 * <p>任务状态用 in-memory ConcurrentHashMap 缓存(不分型用数据库 Job 表,
 *  跟现有的 i2v 任务表解耦,避免影响主任务流)。</p>
 *
 * <p>每个 taskId 关联 ownerUserId,防止越权查询。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EnhancerService {

    private final NewApiClient newApiClient;

    /** taskId → EnhancerJob 内存状态 */
    private final ConcurrentHashMap<String, EnhancerJobResponse> jobStore = new ConcurrentHashMap<>();

    /**
     * 提交画质增强任务。
     * 调用 NewApiClient.submitEnhanceVideo 走 v2v references[] 视频 URL 协议。
     */
    public EnhancerSubmitResponse submit(Long userId, EnhancerSubmitRequest req) {
        if (req == null || req.getVideoUrl() == null || req.getVideoUrl().isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_PARAM, "videoUrl 不能为空");
        }

        log.info("[EnhancerService] submit START: userId={}, version={}, setting={}, videoUrl={}",
            userId, req.getVersion(), req.getSetting(), req.getVideoUrl());

        NewApiClient.SubmitResult result = newApiClient.submitEnhanceVideo(
            req.getVideoUrl(), req.getVersion(), req.getSetting());

        String taskId = result.taskId();
        if (taskId == null || taskId.isBlank()) {
            throw new BusinessException(ErrorCode.NEWAPI_UNREACHABLE,
                "NewAPI 提交后未返回 taskId");
        }

        EnhancerJobResponse job = new EnhancerJobResponse();
        job.setTaskId(taskId);
        job.setStatus("queued");
        job.setVideoUrl(req.getVideoUrl());
        job.setCreatedAt(Instant.now().toEpochMilli());

        // 如果 NewAPI 同步返回了 url(罕见),直接标完成
        if (result.hasUrl()) {
            job.setStatus("completed");
            job.setOutputUrl(result.url());
            job.setCompletedAt(Instant.now().toEpochMilli());
            log.info("[EnhancerService] 同步返回 url,直接标完成: taskId={}, url={}", taskId, result.url());
        }

        jobStore.put(taskId, job);
        log.info("[EnhancerService] submit OK: userId={}, taskId={}, status={}",
            userId, taskId, job.getStatus());

        return new EnhancerSubmitResponse(taskId, job.getStatus());
    }

    /**
     * 查询任务状态。
     * 第一次轮询或缓存中没有时,主动调 NewApiClient.pollVideo 拿最新状态。
     * 已完成的也允许重查(返回缓存值)。
     */
    public EnhancerJobResponse getJob(String taskId, Long userId) {
        if (taskId == null || taskId.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_PARAM, "taskId 不能为空");
        }
        EnhancerJobResponse cached = jobStore.get(taskId);
        if (cached == null) {
            throw new BusinessException(ErrorCode.INVALID_PARAM,
                "任务不存在或已过期: taskId=" + taskId);
        }

        // 简单防越权:缓存里记录 ownerUserId,通过单独 map 关联
        Long owner = ownerStore.get(taskId);
        if (owner != null && !owner.equals(userId)) {
            log.warn("[EnhancerService] 越权访问: userId={}, taskId={}, owner={}", userId, taskId, owner);
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "无权访问该任务");
        }

        // 终态不轮询,直接返回
        String status = cached.getStatus();
        if ("completed".equalsIgnoreCase(status)
            || "succeeded".equalsIgnoreCase(status)
            || "success".equalsIgnoreCase(status)
            || "failed".equalsIgnoreCase(status)
            || "error".equalsIgnoreCase(status)
            || "cancelled".equalsIgnoreCase(status)) {
            return cached;
        }

        // 非终态,调 NewAPI 轮询
        try {
            JsonNode pollResult = newApiClient.pollVideo(taskId);
            String newStatus = pollResult.path("status").asText("unknown");
            cached.setStatus(newStatus);

            if ("completed".equalsIgnoreCase(newStatus)
                || "succeeded".equalsIgnoreCase(newStatus)
                || "success".equalsIgnoreCase(newStatus)) {
                String url = newApiClient.extractVideoUrl(pollResult);
                if (url != null && !url.isBlank()) {
                    cached.setOutputUrl(url);
                    cached.setCompletedAt(Instant.now().toEpochMilli());
                } else {
                    // 完成但没 url(假完成)
                    cached.setStatus("failed");
                    cached.setErrorMessage("NewAPI 标记完成但未返回视频 URL");
                    cached.setCompletedAt(Instant.now().toEpochMilli());
                }
            } else if ("failed".equalsIgnoreCase(newStatus)
                || "error".equalsIgnoreCase(newStatus)
                || "cancelled".equalsIgnoreCase(newStatus)) {
                cached.setErrorMessage(pollResult.toString().length() > 500
                    ? pollResult.toString().substring(0, 500) + "..."
                    : pollResult.toString());
                cached.setCompletedAt(Instant.now().toEpochMilli());
            }

            jobStore.put(taskId, cached);
            return cached;
        } catch (BusinessException e) {
            // 已知业务错误(如 NEWAPI_TASK_NOT_FOUND 任务清理),原样抛
            log.warn("[EnhancerService] pollVideo 业务错误: taskId={}, err={}", taskId, e.getMessage());
            throw e;
        } catch (Exception e) {
            log.error("[EnhancerService] pollVideo 异常: taskId={}, err={}", taskId, e.getMessage());
            throw new BusinessException(ErrorCode.NEWAPI_UNREACHABLE,
                "查询任务状态失败: " + e.getMessage());
        }
    }

    /** taskId → ownerUserId(用于防越权) */
    private final ConcurrentHashMap<String, Long> ownerStore = new ConcurrentHashMap<>();

    /**
     * 包装 submit,在 jobStore.put 前记录 owner。
     * Controller 应调这个方法而不是直接调 submit。
     */
    public EnhancerSubmitResponse submitWithOwner(Long userId, EnhancerSubmitRequest req) {
        EnhancerSubmitResponse resp = submit(userId, req);
        ownerStore.put(resp.getTaskId(), userId);
        return resp;
    }
}
