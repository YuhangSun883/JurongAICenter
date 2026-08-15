package com.jurong.aicenter.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.jurong.aicenter.dto.auth.AuthResponse;
import com.jurong.aicenter.dto.auth.LoginRequest;
import com.jurong.aicenter.dto.auth.RefreshRequest;
import com.jurong.aicenter.dto.auth.RegisterRequest;
import com.jurong.aicenter.entity.ConsoleAdmin;
import com.jurong.aicenter.entity.RevokedToken;
import com.jurong.aicenter.entity.User;
import com.jurong.aicenter.exception.BusinessException;
import com.jurong.aicenter.exception.ErrorCode;
import com.jurong.aicenter.repository.ConsoleAdminRepository;
import com.jurong.aicenter.repository.RevokedTokenRepository;
import com.jurong.aicenter.repository.UserRepository;
import com.jurong.aicenter.security.JwtTokenProvider;
import com.jurong.aicenter.service.AuthService;
import com.jurong.aicenter.service.MediaLibraryService;
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
    private final ConsoleAdminRepository consoleAdminRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider jwtTokenProvider;
    private final RevokedTokenRepository revokedTokenRepository;
    private final MediaLibraryService mediaLibraryService;

    @Override
    public AuthResponse register(RegisterRequest request) {
        LambdaQueryWrapper<User> query = new LambdaQueryWrapper<>();
        query.eq(User::getEmail, request.getEmail());
        if (userRepository.selectOne(query) != null) {
            throw new BusinessException(ErrorCode.EMAIL_ALREADY_EXISTS, "email already exists");
        }

        User user = new User();
        user.setEmail(request.getEmail());
        user.setPasswordHash(passwordEncoder.encode(request.getPassword()));
        String displayName = request.getDisplayName();
        if (displayName == null || displayName.isBlank()) {
            displayName = request.getEmail().substring(0, request.getEmail().indexOf('@'));
        }
        user.setDisplayName(displayName);
        user.setRole("USER");
        user.setDisabled(0);
        user.setCredits(0);
        user.setMonthlyQuota(50);
        user.setQuotaUsed(0);
        user.setQuotaPeriodStart(LocalDate.now());
        user.setPlan("FREE");
        user.setCreatedAt(LocalDateTime.now());
        user.setUpdatedAt(LocalDateTime.now());
        userRepository.insert(user);

        try {
            mediaLibraryService.createDefaultLibraries(user.getId());
        } catch (Exception e) {
            log.warn("createDefaultLibraries failed for userId={}: {}", user.getId(), e.getMessage());
        }

        return buildAppAuthResponse(user);
    }

    @Override
    public AuthResponse login(LoginRequest request) {
        LambdaQueryWrapper<User> query = new LambdaQueryWrapper<>();
        query.eq(User::getEmail, request.getEmail());
        User user = userRepository.selectOne(query);
        if (user == null || !passwordEncoder.matches(request.getPassword(), user.getPasswordHash())) {
            throw new BusinessException(ErrorCode.INVALID_CREDENTIALS, "email or password is incorrect");
        }
        if (user.getDisabled() != null && user.getDisabled() == 1) {
            throw new BusinessException(ErrorCode.USER_DISABLED, "account disabled");
        }
        return buildAppAuthResponse(user);
    }

    @Override
    public AuthResponse refresh(RefreshRequest request) {
        try {
            Claims claims = jwtTokenProvider.parse(request.getRefreshToken());
            if (!"refresh".equals(claims.get("type"))) {
                throw new BusinessException(ErrorCode.INVALID_TOKEN, "invalid refresh token");
            }

            String jti = claims.getId();
            if (jti != null && revokedTokenRepository.countByJti(jti) > 0) {
                throw new BusinessException(ErrorCode.INVALID_TOKEN, "refresh token revoked");
            }

            Long subjectId = Long.valueOf(claims.getSubject());
            String channel = claims.get("channel", String.class);
            if ("CONSOLE".equals(channel)) {
                ConsoleAdmin admin = consoleAdminRepository.selectById(subjectId);
                if (admin == null) {
                    throw new BusinessException(ErrorCode.USER_NOT_FOUND, "console admin not found");
                }
                if (admin.getDisabled() != null && admin.getDisabled() == 1) {
                    throw new BusinessException(ErrorCode.USER_DISABLED, "console admin disabled");
                }
                String newAccessToken = jwtTokenProvider.generateConsoleAccessToken(admin.getId(), admin.getEmail(), admin.getRole());
                return new AuthResponse(newAccessToken, request.getRefreshToken(), admin.getId(), admin.getEmail(), admin.getRole());
            }

            User user = userRepository.selectById(subjectId);
            if (user == null) {
                throw new BusinessException(ErrorCode.USER_NOT_FOUND, "user not found");
            }
            if (user.getDisabled() != null && user.getDisabled() == 1) {
                throw new BusinessException(ErrorCode.USER_DISABLED, "account disabled");
            }
            String newAccessToken = jwtTokenProvider.generateAccessToken(user.getId(), user.getEmail(), user.getRole());
            return new AuthResponse(newAccessToken, request.getRefreshToken(), user.getId(), user.getEmail(), user.getRole());
        } catch (JwtException e) {
            throw new BusinessException(ErrorCode.INVALID_TOKEN, "token invalid or expired");
        }
    }

    @Override
    public void logout(String refreshToken) {
        if (refreshToken == null || refreshToken.isBlank()) {
            log.info("logout called without refreshToken");
            return;
        }
        try {
            Claims claims = jwtTokenProvider.parse(refreshToken);
            if (!"refresh".equals(claims.get("type"))) {
                log.warn("logout called with non-refresh token");
                return;
            }
            String jti = claims.getId();
            Long userId = Long.valueOf(claims.getSubject());
            Instant exp = jwtTokenProvider.getExpiration(refreshToken);
            if (jti != null && revokedTokenRepository.countByJti(jti) > 0) {
                return;
            }

            RevokedToken revoked = new RevokedToken();
            revoked.setJti(jti);
            revoked.setUserId(userId);
            revoked.setRevokedAt(LocalDateTime.now());
            revoked.setExpiresAt(exp.atZone(ZoneId.systemDefault()).toLocalDateTime());
            revokedTokenRepository.insert(revoked);
        } catch (JwtException e) {
            log.warn("logout called with invalid refresh token: {}", e.getMessage());
        }
    }

    private AuthResponse buildAppAuthResponse(User user) {
        String accessToken = jwtTokenProvider.generateAccessToken(user.getId(), user.getEmail(), user.getRole());
        String refreshToken = jwtTokenProvider.generateRefreshToken(user.getId(), user.getEmail(), user.getRole());
        return new AuthResponse(accessToken, refreshToken, user.getId(), user.getEmail(), user.getRole());
    }
}
