package com.jurong.aicenter.dto.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 注册请求 DTO。
 *
 * <p>email 字段：必填 + 宽松邮箱格式（@Email 注解在 Hibernate Validator 8.x 下
 * 严格 regex 会拒绝 [email protected] 这类常见邮箱，所以改用自定义 @Pattern）。
 */
@Data
public class RegisterRequest {

    @NotBlank
    @Pattern(
        regexp = "^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$",
        message = "邮箱格式不正确"
    )
    private String email;

    @NotBlank
    @Size(min = 8, max = 64)
    private String password;

    @Size(max = 100)
    private String displayName;
}