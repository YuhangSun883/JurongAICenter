package com.jurong.aicenter.dto.agent;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class CreateCreditsOrderRequest {
    @NotBlank
    private String packageId;
    private String payMethod;
}
