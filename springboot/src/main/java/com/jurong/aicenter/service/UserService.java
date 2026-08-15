package com.jurong.aicenter.service;

import com.jurong.aicenter.dto.user.QuotaResponse;
import com.jurong.aicenter.dto.user.UserGroupResponse;
import com.jurong.aicenter.dto.user.UserResponse;
import com.jurong.aicenter.dto.user.UserUpdateRequest;

import java.util.List;

public interface UserService {
    UserResponse getCurrentUser(Long userId);
    UserResponse updateCurrentUser(Long userId, UserUpdateRequest request);

    // B6/B7：剥离出去，直接由 QuotaService 处理
    QuotaResponse getUserQuota(Long userId);
    // B10：查询当前用户所属分组
    List<UserGroupResponse> getUserGroups(Long userId);
}