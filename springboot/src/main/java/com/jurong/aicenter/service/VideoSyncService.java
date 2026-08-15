package com.jurong.aicenter.service;

import com.jurong.aicenter.entity.Job;

import java.util.List;

/**
 * 视频同步服务
 * 用于从 NewAPI 主动拉取视频补救 ComfyUI 节点下载失败的情况
 */
public interface VideoSyncService {

    /**
     * 同步视频到 MinIO
     * @param userId 当前用户
     * @param jobId 关联的本地 jobId（用于写 resultUrls）
     * @param newApiTaskId NewAPI 返回的 id(中转站 id,用于 GET /v1/videos/{id} 轮询,见 v3.0 文档 L92)
     * @return 更新后的 Job
     */
    Job syncVideoFromNewApi(Long userId, Long jobId, String newApiTaskId);

    /**
     * 同步视频但不绑定 job（独立生成）
     */
    String syncVideoStandalone(Long userId, String newApiTaskId);
}
