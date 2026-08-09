package com.jurong.aicenter.dto.agent;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class AgentSendResponse {
    private String sessionId;
    private String userMessageId;
    private String assistantMessageId;
    private Integer creditsUsed;
    private Integer creditsEstimated;
}
