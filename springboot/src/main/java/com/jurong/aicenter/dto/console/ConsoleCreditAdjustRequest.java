package com.jurong.aicenter.dto.console;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class ConsoleCreditAdjustRequest {
    @NotNull
    private Integer delta;
    private String reason;
}
