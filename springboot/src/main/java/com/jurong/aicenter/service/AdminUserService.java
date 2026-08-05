package com.jurong.aicenter.service;

import com.jurong.aicenter.dto.admin.AdminUserListItem;
import com.jurong.aicenter.dto.admin.AdminUserSearchRequest;
import com.jurong.aicenter.dto.admin.PageResponse;
import com.jurong.aicenter.dto.admin.UpdateUserRoleRequest;

/**
 * 管理员用户管理服务（Admin 模块 - User 部分）。
 *
 * <p>由 {@link com.jurong.aicenter.controller.AdminController} 调用。
 * 任何失败抛 {@link com.jurong.aicenter.exception.BusinessException}，由 GlobalExceptionHandler 翻译为错误码。</p>
 *
 * <p><b>关键约束</b>：
 * <ul>
 *   <li>严禁修改自己 — 由 {@code currentAdminId} 参数与服务内校验共同保证</li>
 *   <li>所有变更操作必须写审计日志（admin_audit_logs）</li>
 * </ul>
 */
public interface AdminUserService {

    /**
     * 搜索用户（分页）。
     *
     * @param request 搜索条件（displayName LIKE + role 精确 + disabled 过滤）
     * @return 分页结果
     */
    PageResponse<AdminUserListItem> searchUsers(AdminUserSearchRequest request);

    /**
     * 修改用户角色。
     *
     * @param targetUserId 被改的用户
     * @param request 新角色
     * @param currentAdminId 操作者（JWT 内）。等于 {@code targetUserId} 时拒绝。
     * @return 修改后用户的角色
     */
    String updateUserRole(Long targetUserId, UpdateUserRoleRequest request, Long currentAdminId);

    /**
     * 启停用户账号（disabled 字段）。
     *
     * @param targetUserId 被改的用户
     * @param request disabled 布尔值
     * @param currentAdminId 操作者。等
     * @return 修改后用户的 disabled 状态
     */
    Integer updateUserDisabled(Long targetUserId, Boolean disabled, Long currentAdminId);

    /**
     * 获取单个用户完整信息（管理员视图，含 disabled / groups）。
     */
    AdminUserListItem getUserById(Long targetUserId);
}
