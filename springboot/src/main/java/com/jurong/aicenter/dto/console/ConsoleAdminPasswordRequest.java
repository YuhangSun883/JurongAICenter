package com.jurong.aicenter.dto.console;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class ConsoleAdminPasswordRequest {
    @NotBlank
    private String password;
}
