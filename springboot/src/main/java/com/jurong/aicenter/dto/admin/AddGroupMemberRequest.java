package com.jurong.aicenter.dto.admin;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 管理员向分组加成员的请求。
 *
 * <p>如果用户已在该分组（按唯一约束 uk_user_group 抛 SQL 重复 key 错），返 6007 USER_ALREADY_IN_GROUP。</p>
 *
 * <p>加成员不影响 user_jwt token — 是分组调整而非权限变化。</p>
 */
@Data
public class AddGroupMemberRequest {

    @NotNull(message = "userId 不能为空")
    private Long userId;
}
