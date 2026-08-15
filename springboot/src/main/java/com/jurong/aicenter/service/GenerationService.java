package com.jurong.aicenter.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.jurong.aicenter.dto.generation.GenerateRequest;
import com.jurong.aicenter.dto.generation.GenerateResponse;
import com.jurong.aicenter.entity.Job;

import java.util.List;

/**
 * 生成任务服务 - Phase 4 C 负责完整实现
 *
 * 核心流程：
 *   1. submit(userId, request) → 构造 workflow JSON → 调 ComfyUI /prompt → 写 jobs 表
 *   2. @Scheduled 每 2 秒查 RUNNING 的任务 → 调 ComfyUI /history/{id}
 *      - COMPLETED → 下载产物 → 上传 MinIO → 写 result_urls
 *      - FAILED → 写 error_message
 *      - 仍在 RUNNING → 继续等
 *
 * 计费逻辑在 Phase 8 启用（v1 不扣费）
 */
public interface GenerationService {

    GenerateResponse submit(Long userId, GenerateRequest request);

    Job getJob(Long jobId, Long userId);

    List<Job> listJobs(Long userId, int page, int pageSize);

    /** 内部：定时轮询运行中的任务 */
    void pollRunningJobs();

    /** 健康检查 ComfyUI 是否可达 */
    JsonNode comfyuiHealthCheck();

    /**
     * C8 - 取得任务产物的可访问 URL（24h 签名）。
     * 调用方负责重定向到该 URL。
     */
    String getResultUrl(Long jobId, Long userId, String filename);

    /**
     * C9 - 删除 / 取消任务。
     *   - RUNNING → 先调 ComfyUI /interrupt，再标 CANCELLED
     *   - COMPLETED → 标 DELETED（实际数据保留，避免误删；如需真删可加 purge 选项）
     *   - 其它状态 → 直接返回当前状态
     * @return 最终状态
     */
    String deleteJob(Long jobId, Long userId);
}