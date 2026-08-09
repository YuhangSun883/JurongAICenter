package com.jurong.aicenter.service;

import com.jurong.aicenter.dto.auth.AuthResponse;
import com.jurong.aicenter.dto.auth.LoginRequest;
import com.jurong.aicenter.dto.auth.RegisterRequest;
import com.jurong.aicenter.entity.User;
import com.jurong.aicenter.exception.BusinessException;
import com.jurong.aicenter.repository.UserRepository;
import com.jurong.aicenter.security.JwtTokenProvider;
import com.jurong.aicenter.service.impl.AuthServiceImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock
    private UserRepository userRepository;
    @Mock
    private PasswordEncoder passwordEncoder;
    @Mock
    private JwtTokenProvider jwtTokenProvider;

    @InjectMocks
    private AuthServiceImpl authService;

    @Test
    void register_Success() {
        // 1. 准备输入数据
        RegisterRequest request = new RegisterRequest();
        request.setEmail("test@jurong.com");
        request.setPassword("Passw0rd!");
        request.setDisplayName("测试用户");

        // 2. 模拟依赖行为
        when(userRepository.selectOne(any())).thenReturn(null);
        when(passwordEncoder.encode(anyString())).thenReturn("hashedPassword");

        // 【关键修改点】将 anyLong() 替换为 any()，这样能接受 null 值
        when(jwtTokenProvider.generateAccessToken(any(), anyString())).thenReturn("accessToken");
        when(jwtTokenProvider.generateRefreshToken(any(), anyString())).thenReturn("refreshToken");

        // 3. 调用方法并断言
        AuthResponse response = authService.register(request);
        assertNotNull(response);
        assertEquals("accessToken", response.getAccessToken());
        verify(userRepository).insert(any(User.class));
    }

    @Test
    void login_WithWrongPassword_ShouldThrowException() {
        // 1. 准备数据
        LoginRequest request = new LoginRequest();
        request.setEmail("test@jurong.com");
        request.setPassword("wrongPassword");

        User user = new User();
        user.setEmail("test@jurong.com");
        user.setPasswordHash("hashedPassword");

        // 2. 模拟查出用户，但密码匹配失败
        when(userRepository.selectOne(any())).thenReturn(user);
        when(passwordEncoder.matches("wrongPassword", "hashedPassword")).thenReturn(false);

        // 3. 断言抛出异常
        assertThrows(BusinessException.class, () -> authService.login(request));
    }
}