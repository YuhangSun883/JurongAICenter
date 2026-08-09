package com.jurong.aicenter.dto.prompt;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 编辑提示词请求
 */
@Data
public class UpdatePromptRequest {

    /** 提示词标题（可选） */
    private String title;

    @NotBlank(message = "提示词内容不能为空")
    private String prompt;
}
