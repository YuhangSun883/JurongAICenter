package com.jurong.aicenter.service;

import com.jurong.aicenter.dto.admin.UpdateUserRoleRequest;
import com.jurong.aicenter.entity.User;
import com.jurong.aicenter.exception.BusinessException;
import com.jurong.aicenter.exception.ErrorCode;
import com.jurong.aicenter.repository.UserRepository;
import com.jurong.aicenter.service.impl.AdminUserServiceImpl;
import com.jurong.aicenter.customer.repository.UserGroupMemberRepository;
import com.jurong.aicenter.customer.repository.UserGroupRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AdminUserServiceTest {

    @Mock private UserRepository userRepository;
    @Mock private UserGroupMemberRepository userGroupMemberRepository;
    @Mock private UserGroupRepository userGroupRepository;
    @Mock private AdminAuditService adminAuditService;
    @InjectMocks private AdminUserServiceImpl service;

    @Test
    void updateUserRole_CannotChangeSelf() {
        UpdateUserRoleRequest req = new UpdateUserRoleRequest();
        req.setRole("ADMIN");
        // currentAdminId == targetUserId 应被拒
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.updateUserRole(1L, req, 1L));
        assertEquals(ErrorCode.ADMIN_CANNOT_CHANGE_OWN_ROLE, ex.getErrorCode());
        verify(userRepository, never()).updateById(any());
        verify(adminAuditService, never()).log(any(), any(), any(), any(), any(), any());
    }

    @Test
    void updateUserRole_InvalidValue() {
        UpdateUserRoleRequest req = new UpdateUserRoleRequest();
        req.setRole("GOD");
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.updateUserRole(2L, req, 1L));
        assertEquals(ErrorCode.INVALID_ROLE_VALUE, ex.getErrorCode());
    }

    @Test
    void updateUserRole_Success() {
        UpdateUserRoleRequest req = new UpdateUserRoleRequest();
        req.setRole("ADMIN");

        User target = new User();
        target.setId(2L);
        target.setEmail("u2@x.com");
        target.setRole("USER");

        when(userRepository.selectById(2L)).thenReturn(target);
        when(userRepository.selectById(1L)).thenReturn(makeUser(1L, "admin@x.com", "ADMIN"));
        when(userRepository.updateById(any())).thenReturn(1);

        String newRole = service.updateUserRole(2L, req, 1L);
        assertEquals("ADMIN", newRole);
        verify(userRepository).updateById(any(User.class));
        verify(adminAuditService).log(eq(1L), eq("admin@x.com"),
                eq(AdminAuditService.ACTION_CHANGE_ROLE),
                eq(AdminAuditService.TARGET_USER),
                eq(2L), anyString());
    }

    @Test
    void updateUserDisabled_CannotDisableSelf() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.updateUserDisabled(1L, true, 1L));
        assertEquals(ErrorCode.ADMIN_CANNOT_DISABLE_SELF, ex.getErrorCode());
    }

    @Test
    void updateUserDisabled_UserNotFound() {
        when(userRepository.selectById(2L)).thenReturn(null);
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.updateUserDisabled(2L, true, 1L));
        assertEquals(ErrorCode.USER_NOT_FOUND, ex.getErrorCode());
    }

    @Test
    void updateUserDisabled_Success() {
        User target = new User();
        target.setId(2L);
        target.setEmail("u2@x.com");
        target.setDisabled(0);
        when(userRepository.selectById(2L)).thenReturn(target);
        when(userRepository.selectById(1L)).thenReturn(makeUser(1L, "a@x.com", "ADMIN"));
        when(userRepository.updateById(any())).thenReturn(1);

        Integer newVal = service.updateUserDisabled(2L, true, 1L);
        assertEquals(1, newVal);
        verify(adminAuditService).log(eq(1L), any(), eq(AdminAuditService.ACTION_DISABLE_USER),
                eq(AdminAuditService.TARGET_USER), eq(2L), anyString());
    }

    private User makeUser(long id, String email, String role) {
        User u = new User();
        u.setId(id);
        u.setEmail(email);
        u.setRole(role);
        return u;
    }
}
