package com.jurong.aicenter.dto.console;

/**
 * Runtime setting preview. Secret values are masked in service code.
 */
public record ConsoleSettingItem(
    String group,
    String key,
    String value,
    String note
) {
}
