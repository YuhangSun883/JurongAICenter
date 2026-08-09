package com.jurong.aicenter.dto.prompt;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 提示词响应
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class UserPromptResponse {

    private Long id;

    private String title;

    private String prompt;

    private Integer useCount;

    private LocalDateTime createdAt;
}
