package com.jurong.aicenter.dto.prompt;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 保存提示词请求
 */
@Data
public class SavePromptRequest {

    /** 提示词标题（可选，默认取提示词内容前 50 字符） */
    private String title;

    @NotBlank(message = "提示词内容不能为空")
    private String prompt;
}
