package com.jurong.aicenter.controller;

import com.jurong.aicenter.dto.admin.AdminGroupResponse;
import com.jurong.aicenter.dto.admin.AdminUserListItem;
import com.jurong.aicenter.dto.admin.AddGroupMemberRequest;
import com.jurong.aicenter.dto.admin.CreateGroupRequest;
import com.jurong.aicenter.dto.admin.AdminUserSearchRequest;
import com.jurong.aicenter.dto.admin.PageResponse;
import com.jurong.aicenter.dto.admin.UpdateGroupRequest;
import com.jurong.aicenter.dto.admin.UpdateUserDisabledRequest;
import com.jurong.aicenter.dto.admin.UpdateUserRoleRequest;
import com.jurong.aicenter.exception.BusinessException;
import com.jurong.aicenter.exception.ErrorCode;
import com.jurong.aicenter.security.JwtAuthenticationFilter.AuthenticatedUser;
import com.jurong.aicenter.service.AdminGroupService;
import com.jurong.aicenter.service.AdminUserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/**
 * 管理员模块 — 整合 User / Group 管理。
 *
 * <p>路径前缀 {@code /api/admin/**}，由 SecurityConfig 拦截：必须 ROLE_ADMIN 才能访问。
 * 未授权会返 403（403 已脱离 BusinessException，走 Spring Security Filter 链）。</p>
 *
 * <p><b>前端对接要点</b>：
 * <ul>
 *   <li>所有请求带 {@code Authorization: Bearer <accessToken>}</li>
 *   <li>accessToken 含 role claim；前端可解码展示（仅展示用，权限校验由后端做）</li>
 *   <li>角色变更后，被改用户<b>必须重新登录</b>（或 refresh 取新 token）才能获得新 role</li>
 *   <li>改动用户禁用状态<b>即时</b>生效（但用户当前已签发的 access token 在 2h 内仍可使用；
 *       下一笔 login/refresh 才走数据库校验）</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminController {

    private final AdminUserService adminUserService;
    private final AdminGroupService adminGroupService;

    // ====================== 用户管理 ======================

    /**
     * 搜索用户（GET 版本：参数走 query，max pageSize 100）。
     *
     * <p>Query 参数：
     * <ul>
     *   <li>{@code displayName} （可选）：模糊匹配 display_name 字段</li>
     *   <li>{@code role} （可选）：USER / ADMIN 精确匹配</li>
     *   <li>{@code disabled} （可选）：true / false</li>
     *   <li>{@code page} （可选，默认 1）</li>
     *   <li>{@code pageSize} （可选，默认 20，上限 100）</li>
     * </ul>
     */
    @GetMapping("/users")
    public PageResponse<AdminUserListItem> searchUsers(
            @RequestParam(required = false) String displayName,
            @RequestParam(required = false) String role,
            @RequestParam(required = false) Boolean disabled,
            @RequestParam(required = false, defaultValue = "1") Integer page,
            @RequestParam(required = false, defaultValue = "20") Integer pageSize) {
        AdminUserSearchRequest req = new AdminUserSearchRequest();
        req.setDisplayName(displayName);
        req.setRole(role);
        req.setDisabled(disabled);
        req.setPage(page);
        req.setPageSize(pageSize);
        return adminUserService.searchUsers(req);
    }

    /** 获取单个用户完整信息（含禁用状态、所属分组） */
    @GetMapping("/users/{id}")
    public AdminUserListItem getUser(@PathVariable Long id) {
        return adminUserService.getUserById(id);
    }

    /**
     * 修改用户角色。
     *
     * <p>请求体：{@code {"role":"USER"}} 或 {@code {"role":"ADMIN"}}</p>
     * <p><b>严禁修改自己</b>，调用者 id == path id 时返 6002。</p>
     * <p><b>生效时间</b>：被改用户需重新登录（或 refresh）取带新 role 的 token。</p>
     */
    @PatchMapping("/users/{id}/role")
    public String updateUserRole(
            @PathVariable Long id,
            @Valid @RequestBody UpdateUserRoleRequest request,
            @AuthenticationPrincipal AuthenticatedUser principal) {
        Long adminId = requireAdmin(principal);
        return adminUserService.updateUserRole(id, request, adminId);
    }

    /**
     * 启停账号。
     *
     * <p>请求体：{@code {"disabled":true}} 或 {@code {"disabled":false}}</p>
     * <p><b>严禁禁自己</b>，调用者 id == path id 时返 6003。</p>
     * <p><b>生效时间</b>：即时（login 路径会查 DB）。</p>
     */
    @PatchMapping("/users/{id}/disabled")
    public Integer updateUserDisabled(
            @PathVariable Long id,
            @Valid @RequestBody UpdateUserDisabledRequest request,
            @AuthenticationPrincipal AuthenticatedUser principal) {
        Long adminId = requireAdmin(principal);
        return adminUserService.updateUserDisabled(id, request.getDisabled(), adminId);
    }

    // ====================== 客户分组管理 ======================

    /** 列出全部分组 */
    @GetMapping("/groups")
    public PageResponse<AdminGroupResponse> listGroups(
            @RequestParam(required = false, defaultValue = "1") Integer page,
            @RequestParam(required = false, defaultValue = "20") Integer pageSize) {
        return adminGroupService.listGroups(page, pageSize);
    }

    /** 分组详情 */
    @GetMapping("/groups/{id}")
    public AdminGroupResponse getGroup(@PathVariable Long id) {
        return adminGroupService.getGroup(id);
    }

    /** 创建分组 */
    @PostMapping("/groups")
    public AdminGroupResponse createGroup(
            @Valid @RequestBody CreateGroupRequest request,
            @AuthenticationPrincipal AuthenticatedUser principal) {
        Long adminId = requireAdmin(principal);
        return adminGroupService.createGroup(request, adminId);
    }

    /** 修改分组（所有字段可选） */
    @PatchMapping("/groups/{id}")
    public AdminGroupResponse updateGroup(
            @PathVariable Long id,
            @Valid @RequestBody UpdateGroupRequest request,
            @AuthenticationPrincipal AuthenticatedUser principal) {
        Long adminId = requireAdmin(principal);
        return adminGroupService.updateGroup(id, request, adminId);
    }

    /** 删除分组（软删）。Default 不可删（返 6005） */
    @DeleteMapping("/groups/{id}")
    public void deleteGroup(
            @PathVariable Long id,
            @AuthenticationPrincipal AuthenticatedUser principal) {
        Long adminId = requireAdmin(principal);
        adminGroupService.deleteGroup(id, adminId);
    }

    /** 列出分组成员 */
    @GetMapping("/groups/{id}/members")
    public PageResponse<AdminUserListItem> listGroupMembers(
            @PathVariable Long id,
            @RequestParam(required = false, defaultValue = "1") Integer page,
            @RequestParam(required = false, defaultValue = "20") Integer pageSize) {
        return adminGroupService.listGroupMembers(id, page, pageSize);
    }

    /** 加成员 */
    @PostMapping("/groups/{id}/members")
    public void addMember(
            @PathVariable Long id,
            @Valid @RequestBody AddGroupMemberRequest request,
            @AuthenticationPrincipal AuthenticatedUser principal) {
        Long adminId = requireAdmin(principal);
        adminGroupService.addMember(id, request.getUserId(), adminId);
    }

    /** 移除成员 */
    @DeleteMapping("/groups/{id}/members/{userId}")
    public void removeMember(
            @PathVariable Long id,
            @PathVariable Long userId,
            @AuthenticationPrincipal AuthenticatedUser principal) {
        Long adminId = requireAdmin(principal);
        adminGroupService.removeMember(id, userId, adminId);
    }

    // ===== 辅助 =====

    private Long requireAdmin(AuthenticatedUser principal) {
        if (principal == null || principal.id() == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
        // Service 层假设调用者是 admin，SecurityConfig 已做 .hasRole("ADMIN") 拦截。
        // 这里不再二次校验角色，避免多余 DB / role 信任传递失真；
        // 如未来要严格校验，可读 principal.role()。
        return principal.id();
    }
}
