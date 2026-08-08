package com.jurong.aicenter.dto.agent;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class RedeemCardResponse {
    private Integer creditsAdded;
    private Integer validDays;
    private String redeemId;
    private Integer remainingCredits;
}
