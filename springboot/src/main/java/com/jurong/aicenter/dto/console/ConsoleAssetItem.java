package com.jurong.aicenter.dto.console;

import java.time.LocalDateTime;

/**
 * Asset row used by console material management.
 */
public record ConsoleAssetItem(
    Long id,
    Long userId,
    String userEmail,
    String type,
    String source,
    String name,
    String mimeType,
    Long sizeBytes,
    String sourceTool,
    String sourceTaskId,
    LocalDateTime createdAt,
    Integer deleted
) {
}
