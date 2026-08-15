package com.jurong.aicenter.dto.console;

import lombok.Data;

@Data
public class ConsoleUserPatchRequest {
    private String role;
    private Boolean disabled;
}
