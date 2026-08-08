package com.jurong.aicenter.dto.agent;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ContactInfoResponse {
    private String title;
    private String description;
    private List<ContactChannel> channels;
    private String footerHint;
}
