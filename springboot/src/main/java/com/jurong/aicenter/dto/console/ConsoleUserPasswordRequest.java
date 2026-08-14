package com.jurong.aicenter.dto.console;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class ConsoleUserPasswordRequest {
    @NotBlank
    private String password;
}
