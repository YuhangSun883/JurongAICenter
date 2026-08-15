package com.jurong.aicenter.dto.console;

import lombok.Data;

@Data
public class ConsoleAdminPatchRequest {
    private String displayName;
    private String role;
    private Boolean disabled;
}
