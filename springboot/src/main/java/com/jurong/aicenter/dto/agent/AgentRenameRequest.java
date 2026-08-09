package com.jurong.aicenter.dto.agent;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class AgentRenameRequest {
    @NotBlank
    @Size(max = 255)
    private String title;
}
