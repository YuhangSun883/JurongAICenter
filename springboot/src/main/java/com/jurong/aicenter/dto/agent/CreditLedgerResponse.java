package com.jurong.aicenter.dto.agent;

import java.util.List;

public record CreditLedgerResponse(
    List<CreditLedgerItem> items,
    long total,
    int page,
    int pageSize
) {}
