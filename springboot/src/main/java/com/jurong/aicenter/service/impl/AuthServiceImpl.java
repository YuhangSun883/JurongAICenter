package com.jurong.aicenter.service.impl;

import com.jurong.aicenter.dto.auth.AuthResponse;
import com.jurong.aicenter.dto.auth.LoginRequest;
import com.jurong.aicenter.dto.auth.RefreshRequest;
import com.jurong.aicenter.dto.auth.RegisterRequest;
import com.jurong.aicenter.dto.user.UserResponse;
import com.jurong.aicenter.entity.RevokedToken;
import com.jurong.aicenter.entity.User;
import com.jurong.aicenter.exception.BusinessException;
import com.jurong.aicenter.exception.ErrorCode;
import com.jurong.aicenter.repository.RevokedTokenRepository;
import com.jurong.aicenter.repository.UserRepository;
import com.jurong.aicenter.security.JwtTokenProvider;
import com.jurong.aicenter.service.AuthService;
import com.jurong.aicenter.service.MediaLibraryService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthServiceImpl implements AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider jwtTokenProvider;
    private final RevokedTokenRepository revokedTokenRepository;
    private final MediaLibraryService mediaLibraryService; // V8 资产库：注册即建默认库

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
        user.setDisabled(0);
        user.setCredits(0);
        user.setMonthlyQuota(50);
        user.setQuotaUsed(0);
        user.setQuotaPeriodStart(LocalDate.now());
        user.setPlan("FREE");
        user.setCreatedAt(LocalDateTime.now());
        user.setUpdatedAt(LocalDateTime.now());

        // 4. 插入数据库
        userRepository.insert(user);

        // 4.5 V8 资产库：注册即建 2 个系统默认库（"我的资产" + "AI 生成结果"）
        try {
            mediaLibraryService.createDefaultLibraries(user.getId());
        } catch (Exception e) {
            // 默认库创建失败不应阻塞注册（用户能登录后还能补救）
            log.warn("createDefaultLibraries failed for userId={}: {}", user.getId(), e.getMessage());
        }

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

        // 2. 校验禁用状态
        if (user.getDisabled() != null && user.getDisabled() == 1) {
            throw new BusinessException(ErrorCode.USER_DISABLED, "账号已被禁用，请联系管理员");
        }

        // 3. 校验密码
        if (!passwordEncoder.matches(request.getPassword(), user.getPasswordHash())) {
            throw new BusinessException(ErrorCode.INVALID_CREDENTIALS, "邮箱或密码错误");
        }

        // 4. 2026-08-14 新增：管理员账号禁止从普通 /login 页登录
        // 普通 /login 是用户入口，ADMIN 账号必须通过 /admin/login 独立入口登录
        // （如果未来支持独立后台登录路由，校验逻辑可按 entrypoint 区分）
        if ("ADMIN".equals(user.getRole())) {
            throw new BusinessException(ErrorCode.ADMIN_LOGIN_FORBIDDEN_ON_USER_ENTRY,
                    "管理员账号请通过管理后台专属入口登录");
        }

        // 5. 返回响应
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

            // 1.5 检查 refresh token 是否已被撤销（登出 / 改密 / 强制下线）
            String jti = claims.getId();
            if (jti != null && revokedTokenRepository.countByJti(jti) > 0) {
                throw new BusinessException(ErrorCode.INVALID_TOKEN, "Refresh Token 已被撤销，请重新登录");
            }

            // 2. 提取 userId 并查询用户
            Long userId = Long.valueOf(claims.getSubject());
            User user = userRepository.selectById(userId);
            if (user == null) {
                throw new BusinessException(ErrorCode.USER_NOT_FOUND, "用户不存在");
            }

            // 3. 检查禁用（refresh 后也不能用）
            if (user.getDisabled() != null && user.getDisabled() == 1) {
                throw new BusinessException(ErrorCode.USER_DISABLED, "账号已被禁用");
            }

            // 4. 生成新的 Access Token
            // 关键：role 用数据库最新的 —— 这样管理员调整角色后，
            // 用户只要调用 /api/auth/refresh 拿新 access，新 access 立刻有新 role。
            // 注意：access token 有效期 2h 内即便不 refresh 也能用，但 2h 后强制再 refresh，权限自然变更。
            String newAccessToken = jwtTokenProvider.generateAccessToken(user.getId(), user.getEmail(), user.getRole());

            // 5. 返回响应（Refresh Token 保持不变）
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

    @Override
    public void logout(String refreshToken) {
        // 1. 没传 refreshToken：纯前端清理场景，后端无事可做，直接返回成功
        if (refreshToken == null || refreshToken.isBlank()) {
            log.info("logout called without refreshToken (frontend cleanup only)");
            return;
        }

        try {
            // 2. 解析 refresh token，提取 jti 和过期时间
            Claims claims = jwtTokenProvider.parse(refreshToken);
            String type = (String) claims.get("type");
            if (!"refresh".equals(type)) {
                // 不是 refresh token 也算成功（幂等）
                log.warn("logout called with non-refresh token, ignored");
                return;
            }

            String jti = claims.getId();
            Long userId = Long.valueOf(claims.getSubject());
            Instant exp = jwtTokenProvider.getExpiration(refreshToken);

            // 3. 幂等：已撤销的不重复写入
            if (jti != null && revokedTokenRepository.countByJti(jti) > 0) {
                log.info("refresh token already revoked (jti={})", jti);
                return;
            }

            // 4. 写入 revoked_tokens 表
            RevokedToken revoked = new RevokedToken();
            revoked.setJti(jti);
            revoked.setUserId(userId);
            revoked.setRevokedAt(LocalDateTime.now());
            revoked.setExpiresAt(exp.atZone(ZoneId.systemDefault()).toLocalDateTime());
            revokedTokenRepository.insert(revoked);

            log.info("user {} logged out, refresh token jti={} revoked", userId, jti);
        } catch (JwtException e) {
            // token 无效 / 已过期：当作幂等成功（不需要报错）
            log.warn("logout called with invalid refresh token: {}", e.getMessage());
        }
    }

    // ========== 私有辅助方法 ==========

    /**
     * 根据 User 生成 JWT 并组装 AuthResponse。
     * 注意：role 写入 token claim，前端可解析（仅展示用，权限校验由后端 filter 处理）。
     */
    private AuthResponse buildAuthResponse(User user) {
        String accessToken = jwtTokenProvider.generateAccessToken(user.getId(), user.getEmail(), user.getRole());
        String refreshToken = jwtTokenProvider.generateRefreshToken(user.getId(), user.getEmail(), user.getRole());

        return new AuthResponse(
                accessToken,
                refreshToken,
                user.getId(),
                user.getEmail(),
                user.getRole()
        );
    }
}
