package com.jurong.aicenter.dto.console;

import java.time.LocalDateTime;

/**
 * Job row used by console task management.
 */
public record ConsoleJobItem(
    Long id,
    Long userId,
    String userEmail,
    String templateId,
    String taskId,
    String status,
    Integer creditsCost,
    Integer durationMs,
    String errorMessage,
    LocalDateTime createdAt,
    LocalDateTime completedAt
) {
}
