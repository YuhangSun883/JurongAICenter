package com.jurong.aicenter.dto.user;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class UserResponse {
    private Long id;
    private String email;
    private String displayName;
    private String role;
    private Integer credits;
    private Integer monthlyQuota;
    private Integer quotaUsed;
    private String plan;
}