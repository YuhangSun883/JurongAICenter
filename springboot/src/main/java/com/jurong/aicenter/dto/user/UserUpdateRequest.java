package com.jurong.aicenter.dto.user;

import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class UserUpdateRequest {
    
    // 修改昵称，选填
    @Size(min = 2, max = 20, message = "昵称长度必须在 2 到 20 个字符之间")
    private String displayName;

    // 修改密码，选填
    @Size(min = 6, message = "密码长度不能少于 6 个字符")
    private String password;
}