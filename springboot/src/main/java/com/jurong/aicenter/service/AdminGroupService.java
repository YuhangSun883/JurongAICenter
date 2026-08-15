package com.jurong.aicenter.service;

import com.jurong.aicenter.dto.admin.AdminGroupResponse;
import com.jurong.aicenter.dto.admin.AdminUserListItem;
import com.jurong.aicenter.dto.admin.CreateGroupRequest;
import com.jurong.aicenter.dto.admin.PageResponse;
import com.jurong.aicenter.dto.admin.UpdateGroupRequest;

/**
 * 管理员客户分组 + 成员管理服务（Admin 模块 - Group 部分）。
 */
public interface AdminGroupService {

    /** 列出全部分组（含成员数） */
    PageResponse<AdminGroupResponse> listGroups(int page, int pageSize);

    /** 获取分组详情 */
    AdminGroupResponse getGroup(Long groupId);

    /** 创建分组 */
    AdminGroupResponse createGroup(CreateGroupRequest request, Long currentAdminId);

    /** 修改分组 */
    AdminGroupResponse updateGroup(Long groupId, UpdateGroupRequest request, Long currentAdminId);

    /** 软删分组（Default 不可删） */
    void deleteGroup(Long groupId, Long currentAdminId);

    /** 列出分组成员 */
    PageResponse<AdminUserListItem> listGroupMembers(Long groupId, int page, int pageSize);

    /** 加入分组 */
    void addMember(Long groupId, Long userId, Long currentAdminId);

    /** 移除成员 */
    void removeMember(Long groupId, Long userId, Long currentAdminId);
}
