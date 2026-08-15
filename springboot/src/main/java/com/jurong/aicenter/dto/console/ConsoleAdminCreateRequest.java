package com.jurong.aicenter.dto.console;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class ConsoleAdminCreateRequest {
    @Email
    @NotBlank
    private String email;

    @NotBlank
    private String password;

    private String displayName;

    private String role;
}
