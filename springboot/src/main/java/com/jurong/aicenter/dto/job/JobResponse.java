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
}