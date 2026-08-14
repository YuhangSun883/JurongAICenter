package com.jurong.aicenter.dto.console;

import java.time.LocalDateTime;

public record ConsoleAdminItem(
    Long id,
    String email,
    String displayName,
    String role,
    Integer disabled,
    LocalDateTime lastLoginAt,
    LocalDateTime createdAt,
    LocalDateTime updatedAt
) {}
