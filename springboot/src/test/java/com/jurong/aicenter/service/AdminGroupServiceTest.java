package com.jurong.aicenter.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.jurong.aicenter.customer.entity.UserGroup;
import com.jurong.aicenter.customer.repository.UserGroupMemberRepository;
import com.jurong.aicenter.customer.repository.UserGroupRepository;
import com.jurong.aicenter.dto.admin.CreateGroupRequest;
import com.jurong.aicenter.exception.BusinessException;
import com.jurong.aicenter.exception.ErrorCode;
import com.jurong.aicenter.repository.UserRepository;
import com.jurong.aicenter.service.impl.AdminGroupServiceImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AdminGroupServiceTest {

    @Mock private UserGroupRepository userGroupRepository;
    @Mock private UserGroupMemberRepository userGroupMemberRepository;
    @Mock private UserRepository userRepository;
    @Mock private AdminAuditService adminAuditService;
    @InjectMocks private AdminGroupServiceImpl service;

    @Test
    void createGroup_DuplicateName() {
        CreateGroupRequest req = new CreateGroupRequest();
        req.setName("VIP");
        when(userGroupRepository.insert(any(UserGroup.class)))
                .thenThrow(new DuplicateKeyException("dup"));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.createGroup(req, 1L));
        assertEquals(ErrorCode.GROUP_NAME_DUPLICATE, ex.getErrorCode());
        verify(adminAuditService, never()).log(any(), any(), any(), any(), any(), any());
    }

    @Test
    void deleteGroup_Default_CannotDelete() {
        UserGroup g = new UserGroup();
        g.setId(1L);
        g.setName("Default");
        g.setIsDefault(true);
        when(userGroupRepository.selectById(1L)).thenReturn(g);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.deleteGroup(1L, 99L));
        assertEquals(ErrorCode.GROUP_IS_DEFAULT_CANNOT_DELETE, ex.getErrorCode());
        verify(userGroupRepository, never()).deleteById(anyLong());
    }

    @Test
    void deleteGroup_Normal_SoftDelete() {
        UserGroup g = new UserGroup();
        g.setId(2L);
        g.setName("VIP");
        g.setIsDefault(false);
        when(userGroupRepository.selectById(2L)).thenReturn(g);
        when(userGroupRepository.deleteById(2L)).thenReturn(1);
        when(userGroupMemberRepository.delete(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class))).thenReturn(0);

        service.deleteGroup(2L, 1L);
        verify(userGroupRepository).deleteById(2L);
        verify(adminAuditService).log(eq(1L), any(),
                eq(AdminAuditService.ACTION_DELETE_GROUP),
                eq(AdminAuditService.TARGET_GROUP), eq(2L), anyString());
    }

    @Test
    void addMember_UserAlreadyInGroup() {
        UserGroup g = new UserGroup();
        g.setId(2L);
        g.setName("VIP");
        g.setIsDefault(false);
        when(userGroupRepository.selectById(2L)).thenReturn(g);
        when(userGroupMemberRepository.selectCount(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class))).thenReturn(1L);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.addMember(2L, 5L, 1L));
        assertEquals(ErrorCode.USER_ALREADY_IN_GROUP, ex.getErrorCode());
    }

    @Test
    void removeMember_NotInGroup() {
        UserGroup g = new UserGroup();
        g.setId(2L);
        g.setName("VIP");
        g.setIsDefault(false);
        when(userGroupRepository.selectById(2L)).thenReturn(g);
        when(userGroupMemberRepository.delete(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class))).thenReturn(0);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.removeMember(2L, 5L, 1L));
        assertEquals(ErrorCode.USER_NOT_IN_GROUP, ex.getErrorCode());
    }
}
