package com.jurong.aicenter.dto.console;

import java.time.LocalDateTime;

/**
 * User row shown in the new back office user table.
 */
public record ConsoleUserItem(
    Long id,
    String email,
    String displayName,
    String role,
    Integer disabled,
    Integer credits,
    Integer monthlyQuota,
    Integer quotaUsed,
    String plan,
    LocalDateTime createdAt,
    LocalDateTime updatedAt
) {
}
