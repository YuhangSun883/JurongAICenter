package com.jurong.aicenter.controller;

import com.jurong.aicenter.dto.user.QuotaResponse;
import com.jurong.aicenter.dto.user.UserGroupResponse;
import com.jurong.aicenter.dto.user.UserResponse;
import com.jurong.aicenter.dto.user.UserUpdateRequest;
import com.jurong.aicenter.exception.BusinessException;
import com.jurong.aicenter.exception.ErrorCode;
import com.jurong.aicenter.security.JwtAuthenticationFilter.AuthenticatedUser;
import com.jurong.aicenter.service.UserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    // B4：查询当前用户 (GET)
    @GetMapping("/me")
    public UserResponse me(@AuthenticationPrincipal AuthenticatedUser principal) {
        if (principal == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
        return userService.getCurrentUser(principal.id());
    }

    // B5：修改当前用户信息 (PATCH)
    @PatchMapping("/me")
    public UserResponse updateMe(
            @Valid @RequestBody UserUpdateRequest request,
            @AuthenticationPrincipal AuthenticatedUser principal) {

        if (principal == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
        return userService.updateCurrentUser(principal.id(), request);
    }

    // B6：查询当前用户配额
    @GetMapping("/me/quota")
    public QuotaResponse getMyQuota(@AuthenticationPrincipal AuthenticatedUser principal) {
        if (principal == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
        return userService.getUserQuota(principal.id());
    }

    // B10：查询当前用户所属分组
    @GetMapping("/me/groups")
    public List<UserGroupResponse> getMyGroups(@AuthenticationPrincipal AuthenticatedUser principal) {
        if (principal == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
        return userService.getUserGroups(principal.id());
    }
}