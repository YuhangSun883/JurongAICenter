package com.jurong.aicenter.dto.console;

public record ConsolePricingRuleItem(
    String key,
    String scene,
    Integer baseCredits,
    String billingLogic,
    String enabled,
    String note
) {}
