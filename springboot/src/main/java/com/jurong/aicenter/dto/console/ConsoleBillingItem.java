package com.jurong.aicenter.dto.console;

import java.time.LocalDateTime;

public record ConsoleBillingItem(
    Long id,
    Long userId,
    String userEmail,
    Long jobId,
    String type,
    Integer creditsDelta,
    Integer balanceAfter,
    String description,
    String paymentId,
    LocalDateTime createdAt
) {}
