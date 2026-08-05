package com.jurong.aicenter.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.jurong.aicenter.customer.entity.UserGroup;
import com.jurong.aicenter.customer.entity.UserGroupMember;
import com.jurong.aicenter.customer.repository.UserGroupMemberRepository;
import com.jurong.aicenter.customer.repository.UserGroupRepository;
import com.jurong.aicenter.dto.admin.AdminGroupResponse;
import com.jurong.aicenter.dto.admin.AdminUserListItem;
import com.jurong.aicenter.dto.admin.CreateGroupRequest;
import com.jurong.aicenter.dto.admin.PageResponse;
import com.jurong.aicenter.dto.admin.UpdateGroupRequest;
import com.jurong.aicenter.entity.User;
import com.jurong.aicenter.exception.BusinessException;
import com.jurong.aicenter.exception.ErrorCode;
import com.jurong.aicenter.repository.UserRepository;
import com.jurong.aicenter.service.AdminAuditService;
import com.jurong.aicenter.service.AdminGroupService;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Admin Group Service 实现。
 *
 * <p>Default 分组是系统兜底（V2 migration 自动建 + name=Default + is_default=1），
 * 所以本服务对 Default 分组有"不可删、不可去默认身份"的强约束。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminGroupServiceImpl implements AdminGroupService {

    private final UserGroupRepository userGroupRepository;
    private final UserGroupMemberRepository userGroupMemberRepository;
    private final UserRepository userRepository;
    private final AdminAuditService adminAuditService;

    @Override
    public PageResponse<AdminGroupResponse> listGroups(int page, int pageSize) {
        page = page < 1 ? 1 : page;
        pageSize = pageSize < 1 ? 20 : Math.min(pageSize, 100);

        Page<UserGroup> mpPage = Page.of(page, pageSize);
        Page<UserGroup> result = userGroupRepository.selectPage(mpPage,
                new LambdaQueryWrapper<UserGroup>().orderByAsc(UserGroup::getId));

        List<UserGroup> groups = result.getRecords();
        Map<Long, Long> memberCount = countMembersFor(groups.stream().map(UserGroup::getId).collect(Collectors.toList()));

        List<AdminGroupResponse> items = groups.stream().map(g -> toResp(g, memberCount)).collect(Collectors.toList());
        return new PageResponse<>(items, result.getTotal(), page, pageSize);
    }

    @Override
    public AdminGroupResponse getGroup(Long groupId) {
        UserGroup g = mustGet(groupId);
        Map<Long, Long> c = countMembersFor(List.of(groupId));
        return toResp(g, c);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AdminGroupResponse createGroup(CreateGroupRequest request, Long currentAdminId) {
        UserGroup g = new UserGroup();
        g.setName(request.getName().trim());
        g.setDescription(request.getDescription());
        g.setColor(request.getColor() == null ? "#909399" : request.getColor());
        g.setIsDefault(Boolean.TRUE.equals(request.getIsDefault()));
        LocalDateTime now = LocalDateTime.now();
        g.setCreatedAt(now);
        g.setUpdatedAt(now);
        try {
            userGroupRepository.insert(g);
        } catch (DuplicateKeyException e) {
            throw new BusinessException(ErrorCode.GROUP_NAME_DUPLICATE,
                    "分组名称已存在：" + request.getName());
        }
        // is_default = true 时，把其他分组的 is_default 重置为 0
        if (Boolean.TRUE.equals(g.getIsDefault())) {
            ensureOnlyOneDefault(g.getId());
        }
        adminAuditService.log(currentAdminId, findAdminEmail(currentAdminId),
                AdminAuditService.ACTION_CREATE_GROUP, AdminAuditService.TARGET_GROUP,
                g.getId(), String.format("{\"name\":\"%s\",\"isDefault\":%s}",
                        g.getName(), g.getIsDefault()));
        return toResp(g, Map.of(g.getId(), 0L));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AdminGroupResponse updateGroup(Long groupId, UpdateGroupRequest request, Long currentAdminId) {
        UserGroup g = mustGet(groupId);
        String oldName = g.getName();
        Boolean oldIsDefault = g.getIsDefault();
        Map<String, Object> diff = new HashMap<>();

        if (request.getName() != null && !request.getName().isBlank() && !request.getName().equals(g.getName())) {
            diff.put("name", String.format("%s->%s", g.getName(), request.getName()));
            g.setName(request.getName().trim());
        }
        if (request.getDescription() != null) {
            diff.put("description", "*");
            g.setDescription(request.getDescription());
        }
        if (request.getColor() != null) {
            g.setColor(request.getColor());
        }
        if (request.getIsDefault() != null) {
            // Default 分组的 is_default 不可关闭
            if (Boolean.TRUE.equals(oldIsDefault) && !request.getIsDefault()) {
                throw new BusinessException(ErrorCode.GROUP_IS_DEFAULT_CANNOT_UNSET,
                        "默认分组不可关闭 is_default，请新建一个分组并把 is_default 切换过去");
            }
            g.setIsDefault(request.getIsDefault());
        }
        g.setUpdatedAt(LocalDateTime.now());
        try {
            userGroupRepository.updateById(g);
        } catch (DuplicateKeyException e) {
            throw new BusinessException(ErrorCode.GROUP_NAME_DUPLICATE,
                    "分组名称已存在：" + request.getName());
        }
        if (Boolean.TRUE.equals(g.getIsDefault())) {
            ensureOnlyOneDefault(g.getId());
        }
        Map<Long, Long> c = countMembersFor(List.of(g.getId()));
        diff.put("id", g.getId());
        adminAuditService.log(currentAdminId, findAdminEmail(currentAdminId),
                AdminAuditService.ACTION_UPDATE_GROUP, AdminAuditService.TARGET_GROUP,
                g.getId(), diff.toString());
        log.info("admin {} updated group {} diff={}", currentAdminId, g.getId(), diff);
        return toResp(g, c);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteGroup(Long groupId, Long currentAdminId) {
        UserGroup g = mustGet(groupId);
        if (Boolean.TRUE.equals(g.getIsDefault())) {
            throw new BusinessException(ErrorCode.GROUP_IS_DEFAULT_CANNOT_DELETE,
                    "默认分组不可删除（name=" + g.getName() + "），如需替换请新建分组并把 is_default 切换过去");
        }
        // 软删：用 MyBatis Plus @TableLogic 自动转 update deleted=1
        userGroupRepository.deleteById(g.getId());
        // 同时清空该分组下的成员关系（保持数据一致性）
        userGroupMemberRepository.delete(
                new LambdaQueryWrapper<UserGroupMember>().eq(UserGroupMember::getGroupId, g.getId()));
        adminAuditService.log(currentAdminId, findAdminEmail(currentAdminId),
                AdminAuditService.ACTION_DELETE_GROUP, AdminAuditService.TARGET_GROUP,
                groupId, String.format("{\"name\":\"%s\"}", g.getName()));
    }

    @Override
    public PageResponse<AdminUserListItem> listGroupMembers(Long groupId, int page, int pageSize) {
        mustGet(groupId);
        page = page < 1 ? 1 : page;
        pageSize = pageSize < 1 ? 20 : Math.min(pageSize, 100);

        // 1. 拿 userIds 通过中间表分页
        Page<UserGroupMember> mPage = Page.of(page, pageSize);
        Page<UserGroupMember> mResult = userGroupMemberRepository.selectPage(mPage,
                new LambdaQueryWrapper<UserGroupMember>().eq(UserGroupMember::getGroupId, groupId));

        List<Long> userIds = mResult.getRecords().stream().map(UserGroupMember::getUserId).collect(Collectors.toList());
        if (userIds.isEmpty()) {
            return new PageResponse<>(List.of(), 0, page, pageSize);
        }
        // 2. 查 user 实体
        List<User> users = userRepository.selectBatchIds(userIds);
        // 保留分页顺序
        Map<Long, User> byId = users.stream().collect(Collectors.toMap(User::getId, u -> u));
        List<User> ordered = userIds.stream().map(byId::get).filter(java.util.Objects::nonNull).collect(Collectors.toList());

        List<AdminUserListItem> items = new ArrayList<>(ordered.size());
        for (User u : ordered) {
            AdminUserListItem item = new AdminUserListItem(
                    u.getId(), u.getEmail(), u.getDisplayName(), u.getRole(),
                    u.getDisabled() == null ? 0 : u.getDisabled(),
                    u.getCredits(), u.getMonthlyQuota(), u.getQuotaUsed(), u.getPlan(),
                    u.getCreatedAt(), List.of(groupId), List.of()
            );
            items.add(item);
        }
        return new PageResponse<>(items, mResult.getTotal(), page, pageSize);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void addMember(Long groupId, Long userId, Long currentAdminId) {
        mustGet(groupId);
        if (userId == null) {
            throw new BusinessException(ErrorCode.INVALID_PARAM, "userId 不能为空");
        }
        // 校验目标用户存在
        if (userRepository.selectById(userId) == null) {
            throw new BusinessException(ErrorCode.USER_NOT_FOUND, "目标用户不存在");
        }
        // 防重
        Long exists = userGroupMemberRepository.selectCount(
                new LambdaQueryWrapper<UserGroupMember>()
                        .eq(UserGroupMember::getGroupId, groupId)
                        .eq(UserGroupMember::getUserId, userId));
        if (exists != null && exists > 0) {
            throw new BusinessException(ErrorCode.USER_ALREADY_IN_GROUP,
                    "用户（id=" + userId + "）已在该分组（id=" + groupId + "）中");
        }
        UserGroupMember m = new UserGroupMember();
        m.setGroupId(groupId);
        m.setUserId(userId);
        m.setJoinedAt(LocalDateTime.now());
        try {
            userGroupMemberRepository.insert(m);
        } catch (DuplicateKeyException e) {
            // 并发添加重复 → 同 USER_ALREADY_IN_GROUP
            throw new BusinessException(ErrorCode.USER_ALREADY_IN_GROUP,
                    "用户（id=" + userId + "）已在该分组中");
        }
        adminAuditService.log(currentAdminId, findAdminEmail(currentAdminId),
                AdminAuditService.ACTION_ADD_GROUP_MEMBER, AdminAuditService.TARGET_GROUP_MEMBER,
                userId, String.format("{\"groupId\":%d,\"userId\":%d}", groupId, userId));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void removeMember(Long groupId, Long userId, Long currentAdminId) {
        mustGet(groupId);
        if (userId == null) {
            throw new BusinessException(ErrorCode.INVALID_PARAM, "userId 不能为空");
        }
        int rows = userGroupMemberRepository.delete(
                new LambdaQueryWrapper<UserGroupMember>()
                        .eq(UserGroupMember::getGroupId, groupId)
                        .eq(UserGroupMember::getUserId, userId));
        if (rows == 0) {
            throw new BusinessException(ErrorCode.USER_NOT_IN_GROUP,
                    "用户（id=" + userId + "）不在该分组（id=" + groupId + "）中");
        }
        adminAuditService.log(currentAdminId, findAdminEmail(currentAdminId),
                AdminAuditService.ACTION_REMOVE_GROUP_MEMBER, AdminAuditService.TARGET_GROUP_MEMBER,
                userId, String.format("{\"groupId\":%d,\"userId\":%d}", groupId, userId));
    }

    // ===== 辅助 =====

    private UserGroup mustGet(Long id) {
        UserGroup g = userGroupRepository.selectById(id);
        if (g == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "分组不存在（id=" + id + "）");
        }
        return g;
    }

    /**
     * 在 (groupId) 列表上一次性 GROUP BY count(*) → {groupId: count}。
     * 用 MP 默认的 BaseMapper 自定义 SQL 不便，用 QueryWrapper 替代：
     * 这里走 XML 更清晰，但为了 B 模块最小化跨文件改动，用 {@link QueryWrapper} 走 selectMaps。
     */
    private Map<Long, Long> countMembersFor(List<Long> groupIds) {
        if (groupIds == null || groupIds.isEmpty()) return Map.of();
        QueryWrapper<UserGroupMember> q = new QueryWrapper<>();
        q.select("group_id AS groupId, COUNT(*) AS cnt")
                .in("group_id", groupIds)
                .eq("deleted", 0)
                .groupBy("group_id");
        List<Map<String, Object>> rows = userGroupMemberRepository.selectMaps(q);
        Map<Long, Long> result = new HashMap<>();
        for (Map<String, Object> r : rows) {
            Object gidObj = r.get("groupId");
            Object cntObj = r.get("cnt");
            if (gidObj != null && cntObj != null) {
                result.put(Long.valueOf(gidObj.toString()), Long.valueOf(cntObj.toString()));
            }
        }
        return result;
    }

    /** 确保只有 gId 自己 is_default=true；其他全部设 0 */
    private void ensureOnlyOneDefault(Long gId) {
        UserGroup reset = new UserGroup();
        reset.setIsDefault(false);
        reset.setUpdatedAt(LocalDateTime.now());
        userGroupRepository.update(reset,
                new LambdaQueryWrapper<UserGroup>()
                        .ne(UserGroup::getId, gId)
                        .eq(UserGroup::getIsDefault, true));
    }

    private AdminGroupResponse toResp(UserGroup g, Map<Long, Long> memberCount) {
        return new AdminGroupResponse(
                g.getId(),
                g.getName(),
                g.getDescription(),
                g.getColor(),
                g.getIsDefault(),
                memberCount.getOrDefault(g.getId(), 0L),
                g.getCreatedAt(),
                g.getUpdatedAt()
        );
    }

    private String findAdminEmail(Long adminId) {
        if (adminId == null) return "";
        User u = userRepository.selectById(adminId);
        return u == null ? "" : u.getEmail();
    }
}
