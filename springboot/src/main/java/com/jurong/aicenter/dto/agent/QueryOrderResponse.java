package com.jurong.aicenter.dto.agent;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class QueryOrderResponse {
    private String orderId;
    private String status;
    private Long paidAt;
    private String failReason;
    private Integer amount;
    private String planId;
    private Receipt receipt;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Receipt {
        private Integer creditsAdded;
        private Integer validDays;
    }
}
