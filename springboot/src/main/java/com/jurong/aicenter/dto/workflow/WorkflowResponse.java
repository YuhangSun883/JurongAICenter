package com.jurong.aicenter.dto.workflow;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@AllArgsConstructor
public class WorkflowResponse {
    private Long id;
    private String name;
    private String description;
    private String graphJson;
    private String thumbnailUrl;
    private Boolean isPublic;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}