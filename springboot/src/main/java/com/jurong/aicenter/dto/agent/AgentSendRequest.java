package com.jurong.aicenter.dto.agent;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

@Data
public class AgentSendRequest {
    /** null = 创建新对话 */
    private String sessionId;

    @NotBlank
    @Size(max = 8000)
    private String content;

    private List<String> attachmentIds;
    private List<String> tools;
    private List<String> roleIds;
}
