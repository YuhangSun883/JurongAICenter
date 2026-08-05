package com.jurong.aicenter.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.jurong.aicenter.customer.entity.UserGroup;
import com.jurong.aicenter.customer.entity.UserGroupMember;
import com.jurong.aicenter.customer.repository.UserGroupMemberRepository;
import com.jurong.aicenter.customer.repository.UserGroupRepository;
import com.jurong.aicenter.dto.user.QuotaResponse;
import com.jurong.aicenter.dto.user.UserGroupResponse;
import com.jurong.aicenter.dto.user.UserResponse;
import com.jurong.aicenter.dto.user.UserUpdateRequest;
import com.jurong.aicenter.entity.User;
import com.jurong.aicenter.exception.BusinessException;
import com.jurong.aicenter.exception.ErrorCode;
import com.jurong.aicenter.repository.UserRepository;
import com.jurong.aicenter.service.QuotaService;
import com.jurong.aicenter.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class UserServiceImpl implements UserService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder; // 用于 BCrypt 加密
    private final QuotaService quotaService;
    private final UserGroupMemberRepository userGroupMemberRepository;
    private final UserGroupRepository userGroupRepository;
    @Override
    public UserResponse getCurrentUser(Long userId) {
        User user = userRepository.selectById(userId);
        if (user == null) {
            throw new BusinessException(ErrorCode.USER_NOT_FOUND, "用户不存在");
        }
        return buildUserResponse(user);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public UserResponse updateCurrentUser(Long userId, UserUpdateRequest request) {
        User user = userRepository.selectById(userId);
        if (user == null) {
            throw new BusinessException(ErrorCode.USER_NOT_FOUND, "用户不存在");
        }

        // 更新昵称
        if (request.getDisplayName() != null && !request.getDisplayName().isBlank()) {
            user.setDisplayName(request.getDisplayName());
        }

        // 更新密码（如果前端传了密码，用 BCrypt 重新加密）
        if (request.getPassword() != null && !request.getPassword().isBlank()) {
            user.setPasswordHash(passwordEncoder.encode(request.getPassword()));
        }

        // 保存到数据库
        userRepository.updateById(user);

        // 返回最新信息
        return buildUserResponse(user);
    }

    // 辅助方法：用你 Controller 里那种 8 个参数的构造函数返回 DTO
    private UserResponse buildUserResponse(User user) {
        return new UserResponse(
            user.getId(),
            user.getEmail(),
            user.getDisplayName(),
            user.getRole(),
            user.getCredits(),
            user.getMonthlyQuota(),
            user.getQuotaUsed(),
            user.getPlan()
        );
    }

    @Override
    public QuotaResponse getUserQuota(Long userId) {
        // 直接调用 B7 的 QuotaService，完全符合文档的职责分离
        return quotaService.getCurrentUsage(userId);
    }

    @Override
    public List<UserGroupResponse> getUserGroups(Long userId) {
        // 1. 从关联表查该用户关联的 groupId
        LambdaQueryWrapper<UserGroupMember> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(UserGroupMember::getUserId, userId);
        List<UserGroupMember> members = userGroupMemberRepository.selectList(wrapper);

        if (members.isEmpty()) {
            return new ArrayList<>();
        }

        // 2. 提取所有 groupId
        List<Long> groupIds = members.stream()
                .map(UserGroupMember::getGroupId)
                .collect(Collectors.toList());

        // 3. 根据 groupIds 查分组详情
        LambdaQueryWrapper<UserGroup> groupWrapper = new LambdaQueryWrapper<>();
        groupWrapper.in(UserGroup::getId, groupIds);
        List<UserGroup> groups = userGroupRepository.selectList(groupWrapper);

        // 4. 转换并返回（省略了 deleted 字段）
        return groups.stream().map(this::convertToResponse).collect(Collectors.toList());
    }
    // 辅助转换方法
    private UserGroupResponse convertToResponse(UserGroup group) {
        UserGroupResponse response = new UserGroupResponse();
        response.setId(group.getId());
        response.setName(group.getName());
        response.setDescription(group.getDescription());
        response.setColor(group.getColor());
        return response;
    }
}