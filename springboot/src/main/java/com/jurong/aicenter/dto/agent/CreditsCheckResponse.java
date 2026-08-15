package com.jurong.aicenter.dto.agent;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class CreditsCheckResponse {
    private String status;
    private Integer remaining;
    private Integer required;
    private Integer code;
    private String message;
    private String upgradeUrl;
}
