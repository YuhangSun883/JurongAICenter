package com.jurong.aicenter.dto.agent;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class CreatePlanOrderRequest {
    @NotBlank
    private String planId;
    private String payMethod;
}
