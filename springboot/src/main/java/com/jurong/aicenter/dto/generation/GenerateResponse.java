package com.jurong.aicenter.dto.generation;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class GenerateResponse {
    private Long jobId;
    private String status;
    private String comfyuiPromptId;
}