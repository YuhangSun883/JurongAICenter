package com.jurong.aicenter.dto.agent;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class CreditPackage {
    private String id;
    private Integer price;
    private Integer credits;
    private Boolean highlighted;
}
