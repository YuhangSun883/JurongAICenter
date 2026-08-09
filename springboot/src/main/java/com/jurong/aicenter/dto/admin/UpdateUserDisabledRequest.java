package com.jurong.aicenter.dto.admin;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 管理员启停账号的请求。
 *
 * <p>disabled=true → 用户无法登录、无法 refresh token（login 时返 2002 USER_DISABLED）。</p>
 *
 * <p><b>重要约束</b>：
 * <ul>
 *   <li>严禁管理员禁用自己的账号 — 由 Service 拦截，调用方报 6003 (ADMIN_CANNOT_DISABLE_SELF)</li>
 *   <li>禁用<b>即时</b>生效，不需要重新登录（login 路径走数据库校验）</li>
 * </ul>
 */
@Data
public class UpdateUserDisabledRequest {

    @NotNull(message = "disabled 不能为空")
    private Boolean disabled;
}
