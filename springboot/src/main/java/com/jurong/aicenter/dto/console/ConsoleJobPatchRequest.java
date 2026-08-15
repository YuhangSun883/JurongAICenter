package com.jurong.aicenter.dto.console;

import lombok.Data;

@Data
public class ConsoleJobPatchRequest {
    private String status;
    private String reason;
}
