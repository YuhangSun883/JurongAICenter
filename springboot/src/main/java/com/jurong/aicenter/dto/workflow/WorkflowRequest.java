package com.jurong.aicenter.dto.workflow;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class WorkflowRequest {

    @NotBlank
    private String name;

    private String description;

    /** workflow graph JSON 字符串 */
    @NotBlank
    private String graphJson;

    private String thumbnailUrl;

    private Boolean isPublic = false;
}