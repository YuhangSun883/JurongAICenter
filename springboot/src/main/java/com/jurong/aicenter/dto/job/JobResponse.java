package com.jurong.aicenter.dto.job;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
@AllArgsConstructor
public class JobResponse {
    private Long id;
    private Long workflowId;
    private String templateId;
    private String status;
    private Integer creditsCost;
    private Integer durationMs;
    private List<String> resultUrls;
    private String errorMessage;
    private LocalDateTime createdAt;
    private LocalDateTime completedAt;
    /** 对应的 media_asset.id（视频完成后已入库时有值）。前端用这个调 /api/media/assets/{mediaAssetId}/stream */
    private Long mediaAssetId;
}