package com.jurong.aicenter.service;

import com.jurong.aicenter.dto.user.UserResponse;
import com.jurong.aicenter.dto.user.UserUpdateRequest;
import com.jurong.aicenter.entity.User;
import com.jurong.aicenter.repository.UserRepository;
import com.jurong.aicenter.service.impl.UserServiceImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    @Mock
    private UserRepository userRepository;
    @Mock
    private PasswordEncoder passwordEncoder;

    @InjectMocks
    private UserServiceImpl userService;

    @Test
    void updateCurrentUser_Success() {
        // 1. 准备数据
        Long userId = 1L;
        User existingUser = new User();
        existingUser.setId(userId);
        existingUser.setDisplayName("OldName");
        existingUser.setPasswordHash("OldHash");

        UserUpdateRequest request = new UserUpdateRequest();
        request.setDisplayName("NewName");
        request.setPassword("NewPass123");

        // 2. 模拟查库和加密
        when(userRepository.selectById(userId)).thenReturn(existingUser);
        when(passwordEncoder.encode("NewPass123")).thenReturn("NewHash");

        // 3. 执行更新
        UserResponse response = userService.updateCurrentUser(userId, request);

        // 4. 断言结果
        assertEquals("NewName", response.getDisplayName());
        verify(userRepository).updateById(any(User.class)); // 验证更新操作执行了
    }
}