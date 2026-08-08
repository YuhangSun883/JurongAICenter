package com.jurong.aicenter.dto.agent;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class CreatePlanOrderResponse {
    private String orderId;
    private String payUrl;
    private Integer amount;
    private String qrCodeUrl;
    private String qrCodeContent;
    private String payMethod;
    private Long expireAt;
    private String status;
}
