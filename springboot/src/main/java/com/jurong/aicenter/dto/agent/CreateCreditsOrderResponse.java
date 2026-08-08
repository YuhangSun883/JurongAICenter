package com.jurong.aicenter.dto.agent;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class CreateCreditsOrderResponse {
    private String orderId;
    private String payUrl;
    private Integer amount;
    private Integer credits;
    private String qrCodeUrl;
    private String qrCodeContent;
    private String payMethod;
    private Long expireAt;
    private String status;
}
