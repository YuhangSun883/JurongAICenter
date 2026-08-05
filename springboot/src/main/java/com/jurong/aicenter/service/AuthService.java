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

    /**
     * 登出 — 撤销 refresh token。
     *
     * <p>access token 由于是无状态 JWT，无法主动失效；
     * 但只要 refresh 被撤销，access 在 2h 自然过期后用户就必须重新登录。</p>
     *
     * @param refreshToken 待撤销的 refresh token（可为 null，此时仅返回成功，不做任何操作）
     */
    void logout(String refreshToken);
}