package com.jurong.aicenter.dto.user;

import lombok.Data;

@Data
public class UserGroupResponse {
    private Long id;
    private String name;
    private String description;
    private String color;
}