package com.jurong.aicenter.dto.agent;

import lombok.Data;

import java.util.Map;

@Data
public class CreditsCheckRequest {
    private String action;
    private Integer estimated;
    private Map<String, Object> context;
}
