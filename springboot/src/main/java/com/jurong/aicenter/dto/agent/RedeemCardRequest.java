package com.jurong.aicenter.dto.agent;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class RedeemCardRequest {
    @NotBlank
    private String code;
}
