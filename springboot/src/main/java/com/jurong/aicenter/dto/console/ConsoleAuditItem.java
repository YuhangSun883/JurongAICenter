package com.jurong.aicenter.dto.console;

import java.time.LocalDateTime;

/**
 * Audit row for console operation history.
 */
public record ConsoleAuditItem(
    Long id,
    Long adminId,
    String adminEmail,
    String action,
    String targetType,
    Long targetId,
    String detail,
    LocalDateTime createdAt
) {
}
