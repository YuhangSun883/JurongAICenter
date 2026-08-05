package com.jurong.aicenter.service;

import com.jurong.aicenter.dto.auth.AuthResponse;
import com.jurong.aicenter.dto.auth.LoginRequest;
import com.jurong.aicenter.dto.auth.RefreshRequest;
import com.jurong.aicenter.dto.auth.RegisterRequest;

/**
 * 鉴权模块（Phase 3 - B 负责）
 *
 * TODO(B):
 *   - 实现 register：bcrypt 加密密码 + 插库 + 签发 token
 *   - 实现 login：校验邮箱 + 密码 + 签发 token
 *   - 实现 refresh：用 refreshToken 换新 accessToken
 *   - 密码强度校验（@Valid 已经在 DTO 上，但需业务层校验）
 *   - 注册时检查邮箱是否已存在（EMAIL_ALREADY_EXISTS 错误码）
 */
public interface AuthService {

    AuthResponse register(RegisterRequest request);

    AuthResponse login(LoginRequest request);

    AuthResponse refresh(RefreshRequest request);
}