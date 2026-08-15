package com.jurong.aicenter.dto.console;

import java.util.List;

/**
 * Back-office pagination DTO. Kept under console to avoid reusing old admin DTOs.
 */
public record ConsolePage<T>(
    List<T> items,
    long total,
    int page,
    int pageSize
) {
}
