package com.jurong.aicenter.dto.admin;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

/**
 * 管理员修改用户角色的请求。
 *
 * <p>只允许 USER ↔ ADMIN 的两态切换，没有 MODERATOR / SUPER_ADMIN 之类的层级。</p>
 *
 * <p><b>重要约束</b>：
 * <ul>
 *   <li>严禁管理员修改<b>自己的</b>角色 — 由 Service 拦截，调用方报 6002 (ADMIN_CANNOT_CHANGE_OWN_ROLE)</li>
 *   <li>被修改的用户的现有 JWT token 在 2h 之内仍带旧 role — 必须重新登录（或调 /api/auth/refresh）后生效</li>
 * </ul>
 */
@Data
public class UpdateUserRoleRequest {

    @NotBlank(message = "role 不能为空")
    @Pattern(regexp = "^(USER|ADMIN)$", message = "role 必须是 USER 或 ADMIN")
    private String role;
}
