package com.jurong.aicenter.dto.agent;

import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class AgentCreateSessionRequest {
    @Size(max = 255)
    private String title;
}
