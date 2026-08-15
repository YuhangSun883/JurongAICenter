package com.jurong.aicenter.event;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 视频 Job 完成后发出的 Spring 事件。
 *
 * <p>由 {@code VideoGenerationServiceImpl.handleCompleted} 在 markCompleted 后发布,
 * 监听器可通过 {@code jobId} 反向定位 CanvasTask 并同步 resultUrl(画布文生视频场景)。
 */
@Getter
@AllArgsConstructor
public class VideoJobCompletedEvent {

    /** Job 主键 ID(自增 Long,来自 jobs 表) */
    private final Long jobId;

    /** MinIO 上的最终视频 URL(已下载+上传+得到访问 URL) */
    private final String videoUrl;
}