package com.jurong.aicenter.service.impl;

import com.jurong.aicenter.dto.auth.AuthResponse;
import com.jurong.aicenter.dto.auth.LoginRequest;
import com.jurong.aicenter.dto.auth.RefreshRequest;
import com.jurong.aicenter.dto.auth.RegisterRequest;
import com.jurong.aicenter.dto.user.UserResponse;
import com.jurong.aicenter.entity.User;
import com.jurong.aicenter.exception.BusinessException;
import com.jurong.aicenter.exception.ErrorCode;
import com.jurong.aicenter.repository.UserRepository;
import com.jurong.aicenter.security.JwtTokenProvider;
import com.jurong.aicenter.service.AuthService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthServiceImpl implements AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider jwtTokenProvider;

    @Override
    public AuthResponse register(RegisterRequest request) {
        // 1. 校验邮箱是否存在
        LambdaQueryWrapper<User> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(User::getEmail, request.getEmail());
        if (userRepository.selectOne(queryWrapper) != null) {
            throw new BusinessException(ErrorCode.EMAIL_ALREADY_EXISTS, "邮箱已被注册");
        }

        // 2. 构建 User 实体
        User user = new User();
        user.setEmail(request.getEmail());
        user.setPasswordHash(passwordEncoder.encode(request.getPassword()));

        String displayName = request.getDisplayName();
        if (displayName == null || displayName.isBlank()) {
            displayName = request.getEmail().substring(0, request.getEmail().indexOf('@'));
        }
        user.setDisplayName(displayName);

        // 3. 设置默认字段
        user.setRole("USER");
        user.setCredits(0);
        user.setMonthlyQuota(50);
        user.setQuotaUsed(0);
        user.setQuotaPeriodStart(LocalDate.now());
        user.setPlan("FREE");
        user.setCreatedAt(LocalDateTime.now());
        user.setUpdatedAt(LocalDateTime.now());

        // 4. 插入数据库
        userRepository.insert(user);

        // 5. 生成 JWT 并返回响应
        return buildAuthResponse(user);
    }

    @Override
    public AuthResponse login(LoginRequest request) {
        // 1. 根据邮箱查用户
        LambdaQueryWrapper<User> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(User::getEmail, request.getEmail());
        User user = userRepository.selectOne(queryWrapper);

        if (user == null) {
            throw new BusinessException(ErrorCode.INVALID_CREDENTIALS, "邮箱或密码错误");
        }

        // 2. 校验密码
        if (!passwordEncoder.matches(request.getPassword(), user.getPasswordHash())) {
            throw new BusinessException(ErrorCode.INVALID_CREDENTIALS, "邮箱或密码错误");
        }

        // 3. 返回响应
        return buildAuthResponse(user);
    }

    @Override
    public AuthResponse refresh(RefreshRequest request) {
        try {
            // 1. 解析 Refresh Token，并校验类型
            Claims claims = jwtTokenProvider.parse(request.getRefreshToken());
            if (!"refresh".equals(claims.get("type"))) {
                throw new BusinessException(ErrorCode.INVALID_TOKEN, "无效的 Refresh Token");
            }

            // 2. 提取 userId 并查询用户
            Long userId = Long.valueOf(claims.getSubject());
            User user = userRepository.selectById(userId);
            if (user == null) {
                throw new BusinessException(ErrorCode.USER_NOT_FOUND, "用户不存在");
            }

            // 3. 生成新的 Access Token（Refresh Token 保持不变）
            String newAccessToken = jwtTokenProvider.generateAccessToken(user.getId(), user.getEmail());

            // 4. 返回响应（使用 5 参数构造器）
            return new AuthResponse(
                    newAccessToken,
                    request.getRefreshToken(),
                    user.getId(),
                    user.getEmail(),
                    user.getRole()
            );
        } catch (JwtException e) {
            throw new BusinessException(ErrorCode.INVALID_TOKEN, "Token 无效或已过期");
        }
    }

    // ========== 私有辅助方法 ==========

    /**
     * 根据 User 生成 JWT 并组装 AuthResponse
     */
    private AuthResponse buildAuthResponse(User user) {
        // 关键修正：按你项目实际方法，必须传入 userId 和 email
        String accessToken = jwtTokenProvider.generateAccessToken(user.getId(), user.getEmail());
        String refreshToken = jwtTokenProvider.generateRefreshToken(user.getId(), user.getEmail());

        return new AuthResponse(
                accessToken,
                refreshToken,
                user.getId(),
                user.getEmail(),
                user.getRole()
        );
    }
}