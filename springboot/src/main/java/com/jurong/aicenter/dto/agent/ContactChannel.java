package com.jurong.aicenter.dto.agent;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ContactChannel {
    private String method;
    private String qrCodeUrl;
    private String qrCodeContent;
    private String value;
    private String description;
}
