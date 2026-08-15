package com.jurong.aicenter.dto.console;

import lombok.Data;

@Data
public class ConsoleUserPlanRequest {
    private String displayName;
    private String plan;
    private Integer monthlyQuota;
}
