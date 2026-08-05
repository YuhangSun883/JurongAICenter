package com.jurong.aicenter.dto.user;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class QuotaResponse {
    private Integer credits;
    private Integer monthlyQuota;
    private Integer quotaUsed;
    private String plan;
}