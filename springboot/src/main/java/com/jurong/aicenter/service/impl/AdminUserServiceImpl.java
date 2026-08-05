package com.jurong.aicenter.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.jurong.aicenter.customer.entity.UserGroup;
import com.jurong.aicenter.customer.entity.UserGroupMember;
import com.jurong.aicenter.customer.repository.UserGroupMemberRepository;
import com.jurong.aicenter.customer.repository.UserGroupRepository;
import com.jurong.aicenter.dto.admin.AdminUserListItem;
import com.jurong.aicenter.dto.admin.AdminUserSearchRequest;
import com.jurong.aicenter.dto.admin.PageResponse;
import com.jurong.aicenter.dto.admin.UpdateUserRoleRequest;
import com.jurong.aicenter.entity.User;
import com.jurong.aicenter.exception.BusinessException;
import com.jurong.aicenter.exception.ErrorCode;
import com.jurong.aicenter.repository.UserRepository;
import com.jurong.aicenter.service.AdminAuditService;
import com.jurong.aicenter.service.AdminUserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 管理员用户管理 — service impl。
 *
 * <p>重点约束：
 * <ul>
 *   <li>严禁改自己 → 由 currentAdminId 校验拦截</li>
 *   <li>每次写操作必走 audit + DB 双事务，audit 失败被 fail-open（AdminAuditService 内部保证）</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminUserServiceImpl implements AdminUserService {

    private final UserRepository userRepository;
    private final UserGroupMemberRepository userGroupMemberRepository;
    private final UserGroupRepository userGroupRepository;
    private final AdminAuditService adminAuditService;

    @Override
    public PageResponse<AdminUserListItem> searchUsers(AdminUserSearchRequest request) {
        // 1. 分页 + 检索条件拼装
        int page = request.getPage() == null || request.getPage() < 1 ? 1 : request.getPage();
        int pageSize = request.getPageSize() == null || request.getPageSize() < 1 ? 20
                : Math.min(request.getPageSize(), 100);

        LambdaQueryWrapper<User> wrapper = new LambdaQueryWrapper<>();
        if (request.getDisplayName() != null && !request.getDisplayName().isBlank()) {
            wrapper.like(User::getDisplayName, request.getDisplayName().trim());
        }
        if (request.getRole() != null && !request.getRole().isBlank()) {
            wrapper.eq(User::getRole, request.getRole().trim());
        }
        if (request.getDisabled() != null) {
            wrapper.eq(User::getDisabled, request.getDisabled() ? 1 : 0);
        }
        wrapper.orderByDesc(User::getCreatedAt);

        Page<User> mpPage = Page.of(page, pageSize);
        Page<User> result = userRepository.selectPage(mpPage, wrapper);

        List<User> users = result.getRecords();
        List<AdminUserListItem> items = users.stream()
                .map(this::toListItem)
                .collect(Collectors.toList());

        // 2. 批量填充 groups（一次 IN 查询，避免 N+1）
        if (!users.isEmpty()) {
            List<Long> userIds = users.stream().map(User::getId).collect(Collectors.toList());
            LambdaQueryWrapper<UserGroupMember> mWrapper = new LambdaQueryWrapper<>();
            mWrapper.in(UserGroupMember::getUserId, userIds);
            List<UserGroupMember> members = userGroupMemberRepository.selectList(mWrapper);

            Map<Long, List<Long>> idToGroupIds = new HashMap<>();
            Map<Long, List<String>> idToGroupNames = new HashMap<>();
            for (UserGroupMember m : members) {
                idToGroupIds.computeIfAbsent(m.getUserId(), k -> new java.util.ArrayList<>()).add(m.getGroupId());
            }
            if (!members.isEmpty()) {
                List<Long> groupIds = members.stream().map(UserGroupMember::getGroupId)
                        .distinct().collect(Collectors.toList());
                LambdaQueryWrapper<UserGroup> gWrapper = new LambdaQueryWrapper<>();
                gWrapper.in(UserGroup::getId, groupIds);
                List<UserGroup> groups = userGroupRepository.selectList(gWrapper);
                Map<Long, String> gidName = groups.stream()
                        .collect(Collectors.toMap(UserGroup::getId, UserGroup::getName));
                for (UserGroupMember m : members) {
                    String name = gidName.get(m.getGroupId());
                    if (name != null) {
                        idToGroupNames.computeIfAbsent(m.getUserId(), k -> new java.util.ArrayList<>()).add(name);
                    }
                }
            }
            for (AdminUserListItem item : items) {
                item.setGroupIds(idToGroupIds.getOrDefault(item.getId(), List.of()));
                item.setGroupNames(idToGroupNames.getOrDefault(item.getId(), List.of()));
            }
        }

        return new PageResponse<>(items, result.getTotal(), page, pageSize);
    }

    @Override
    public AdminUserListItem getUserById(Long targetUserId) {
        User user = userRepository.selectById(targetUserId);
        if (user == null) {
            throw new BusinessException(ErrorCode.USER_NOT_FOUND, "用户不存在");
        }
        return toListItem(user);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public String updateUserRole(Long targetUserId, UpdateUserRoleRequest request, Long currentAdminId) {
        // 1. 严禁改自己
        if (currentAdminId != null && currentAdminId.equals(targetUserId)) {
            throw new BusinessException(ErrorCode.ADMIN_CANNOT_CHANGE_OWN_ROLE,
                    "不能修改自己的角色（adminId=" + currentAdminId + "），请由其他管理员调整");
        }
        // 2. 校验角色取值
        String newRole = request.getRole();
        if (!"USER".equals(newRole) && !"ADMIN".equals(newRole)) {
            throw new BusinessException(ErrorCode.INVALID_ROLE_VALUE,
                    "角色取值必须是 USER 或 ADMIN，得到：" + newRole);
        }
        // 3. 查目标用户
        User user = userRepository.selectById(targetUserId);
        if (user == null) {
            throw new BusinessException(ErrorCode.USER_NOT_FOUND, "用户不存在");
        }
        String oldRole = user.getRole();
        if (oldRole.equals(newRole)) {
            // 没变化：不做 DB 写，直接返回
            return newRole;
        }
        // 4. 写库
        user.setRole(newRole);
        user.setUpdatedAt(LocalDateTime.now());
        userRepository.updateById(user);
        // 5. 审计
        String detail = String.format("{\"fromRole\":\"%s\",\"toRole\":\"%s\",\"targetEmail\":\"%s\"}",
                oldRole, newRole, user.getEmail());
        adminAuditService.log(currentAdminId, findAdminEmail(currentAdminId),
                AdminAuditService.ACTION_CHANGE_ROLE, AdminAuditService.TARGET_USER,
                targetUserId, detail);
        log.info("admin {} changed user {} role: {} -> {}", currentAdminId, targetUserId, oldRole, newRole);
        return newRole;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Integer updateUserDisabled(Long targetUserId, Boolean disabled, Long currentAdminId) {
        // 1. 严禁禁自己
        if (currentAdminId != null && currentAdminId.equals(targetUserId)) {
            throw new BusinessException(ErrorCode.ADMIN_CANNOT_DISABLE_SELF,
                    "不能禁用自己的账号（adminId=" + currentAdminId + "），请由其他管理员处理");
        }
        if (disabled == null) {
            throw new BusinessException(ErrorCode.INVALID_PARAM, "disabled 不能为空");
        }
        // 2. 查目标
        User user = userRepository.selectById(targetUserId);
        if (user == null) {
            throw new BusinessException(ErrorCode.USER_NOT_FOUND, "用户不存在");
        }
        int oldValue = user.getDisabled() == null ? 0 : user.getDisabled();
        int newValue = disabled ? 1 : 0;
        if (oldValue == newValue) {
            return newValue;
        }
        user.setDisabled(newValue);
        user.setUpdatedAt(LocalDateTime.now());
        userRepository.updateById(user);
        // 3. 审计
        String action = newValue == 1 ? AdminAuditService.ACTION_DISABLE_USER : AdminAuditService.ACTION_ENABLE_USER;
        String detail = String.format("{\"from\":%d,\"to\":%d,\"targetEmail\":\"%s\"}",
                oldValue, newValue, user.getEmail());
        adminAuditService.log(currentAdminId, findAdminEmail(currentAdminId), action,
                AdminAuditService.TARGET_USER, targetUserId, detail);
        log.info("admin {} {} user {}", currentAdminId, action, targetUserId);
        return newValue;
    }

    // ===== 辅助方法 =====

    private AdminUserListItem toListItem(User user) {
        return new AdminUserListItem(
                user.getId(),
                user.getEmail(),
                user.getDisplayName(),
                user.getRole(),
                user.getDisabled() == null ? 0 : user.getDisabled(),
                user.getCredits(),
                user.getMonthlyQuota(),
                user.getQuotaUsed(),
                user.getPlan(),
                user.getCreatedAt(),
                List.of(),
                List.of()
        );
    }

    /**
     * 拉管理员邮箱（用于审计冗余 email 字段）。当前调用者不在 Service 内 auth，所以这里走 DB 查。
     * 如果未来引入 SecurityContext.currentUser 缓存，可以省一次 SQL。
     */
    private String findAdminEmail(Long adminId) {
        if (adminId == null) return "";
        User u = userRepository.selectById(adminId);
        return u == null ? "" : u.getEmail();
    }
}
