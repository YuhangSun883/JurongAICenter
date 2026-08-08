package com.jurong.aicenter.dto.prompt;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 保存提示词请求
 */
@Data
public class SavePromptRequest {

    @NotBlank(message = "提示词内容不能为空")
    private String prompt;
}
