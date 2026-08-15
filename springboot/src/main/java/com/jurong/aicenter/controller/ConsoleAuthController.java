package com.jurong.aicenter.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.jurong.aicenter.dto.auth.AuthResponse;
import com.jurong.aicenter.dto.auth.LoginRequest;
import com.jurong.aicenter.entity.ConsoleAdmin;
import com.jurong.aicenter.exception.BusinessException;
import com.jurong.aicenter.exception.ErrorCode;
import com.jurong.aicenter.repository.ConsoleAdminRepository;
import com.jurong.aicenter.security.JwtTokenProvider;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Independent console login. Tokens from /api/auth/login are APP tokens and cannot call /api/console/**.
 */
@RestController
@RequestMapping("/api/console/auth")
@RequiredArgsConstructor
public class ConsoleAuthController {

    private final ConsoleAdminRepository consoleAdminRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider jwtTokenProvider;

    @PostMapping("/login")
    public AuthResponse login(@Valid @RequestBody LoginRequest request) {
        ConsoleAdmin admin = consoleAdminRepository.selectOne(
            new LambdaQueryWrapper<ConsoleAdmin>().eq(ConsoleAdmin::getEmail, request.getEmail())
        );
        if (admin == null || !passwordEncoder.matches(request.getPassword(), admin.getPasswordHash())) {
            throw new BusinessException(ErrorCode.INVALID_CREDENTIALS, "email or password is incorrect");
        }
        if (admin.getDisabled() != null && admin.getDisabled() == 1) {
            throw new BusinessException(ErrorCode.USER_DISABLED, "account disabled");
        }
        admin.setLastLoginAt(java.time.LocalDateTime.now());
        admin.setUpdatedAt(java.time.LocalDateTime.now());
        consoleAdminRepository.updateById(admin);
        return new AuthResponse(
            jwtTokenProvider.generateConsoleAccessToken(admin.getId(), admin.getEmail(), admin.getRole()),
            jwtTokenProvider.generateConsoleRefreshToken(admin.getId(), admin.getEmail(), admin.getRole()),
            admin.getId(),
            admin.getEmail(),
            admin.getDisplayName(),
            admin.getRole(),
            admin.getCreatedAt() == null ? null : admin.getCreatedAt().toString()
        );
    }
}
