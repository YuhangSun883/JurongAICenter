package com.jurong.aicenter.dto.canvas;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class UpdateCanvasRequest {

    @NotBlank
    @Size(max = 255)
    private String name;
}