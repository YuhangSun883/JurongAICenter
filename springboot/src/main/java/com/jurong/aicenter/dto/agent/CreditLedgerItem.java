package com.jurong.aicenter.dto.agent;

import java.time.LocalDateTime;

public record CreditLedgerItem(
    Long id,
    Long jobId,
    String type,
    Integer creditsDelta,
    Integer balanceAfter,
    String description,
    String paymentId,
    String tool,
    LocalDateTime createdAt
) {}
