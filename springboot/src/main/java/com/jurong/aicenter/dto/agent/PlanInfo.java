package com.jurong.aicenter.dto.agent;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class PlanInfo {
    private String id;
    private String title;
    private String badge;
    private Integer price;
    private Integer originalPrice;
    private String description;
    private Integer credits;
    private Integer validDays;
    private List<String> features;
    private Boolean highlighted;
    private String cta;
}
