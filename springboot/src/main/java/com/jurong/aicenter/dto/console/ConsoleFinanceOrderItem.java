package com.jurong.aicenter.dto.console;

import java.time.LocalDateTime;

public record ConsoleFinanceOrderItem(
    String orderNo,
    Long userId,
    String userEmail,
    String source,
    String status,
    Integer amount,
    Integer credits,
    String paymentId,
    LocalDateTime paidAt
) {}
