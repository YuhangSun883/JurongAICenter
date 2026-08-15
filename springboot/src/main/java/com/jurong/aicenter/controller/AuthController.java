package com.jurong.aicenter.controller;

import com.jurong.aicenter.dto.auth.AuthResponse;
import com.jurong.aicenter.dto.auth.LoginRequest;
import com.jurong.aicenter.dto.auth.RefreshRequest;
import com.jurong.aicenter.dto.auth.RegisterRequest;
import com.jurong.aicenter.service.AuthService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @PostMapping("/register")
    public AuthResponse register(@Valid @RequestBody RegisterRequest request) {
        return authService.register(request);
    }

    @PostMapping("/login")
    public AuthResponse login(@Valid @RequestBody LoginRequest request) {
        return authService.login(request);
    }

    @PostMapping("/refresh")
    public AuthResponse refresh(@Valid @RequestBody RefreshRequest request) {
        return authService.refresh(request);
    }

    /**
     * 登出 — 撤销 refresh token。
     *
     * <p>请求体：{ refreshToken: string }（可选；不传则后端无事可做，返回成功）</p>
     * <p>行为：
     * <ul>
     *   <li>把 refresh token 的 jti 写入 revoked_tokens 表</li>
     *   <li>该 refresh token 不能再用于换新 access token</li>
     *   <li>access token 由于无状态，在 2h 有效期内仍可使用（前端应清理 localStorage）</li>
     * </ul>
     *
     * <p>幂等：重复调用 / 无效 token / 已撤销 token 都返回成功。</p>
     */
    @PostMapping("/logout")
    public java.util.Map<String, Object> logout(@RequestBody(required = false) java.util.Map<String, String> body) {
        String refreshToken = body == null ? null : body.get("refreshToken");
        authService.logout(refreshToken);
        return java.util.Map.of("code", 0, "message", "success");
    }
}