package com.jurong.aicenter.dto.canvas;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 新建画布请求。
 */
@Data
public class CreateCanvasRequest {

    @NotBlank
    @Size(max = 255)
    private String name;
}