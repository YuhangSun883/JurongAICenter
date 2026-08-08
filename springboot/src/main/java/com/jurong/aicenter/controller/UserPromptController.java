package com.jurong.aicenter.controller;

import com.jurong.aicenter.dto.prompt.SavePromptRequest;
import com.jurong.aicenter.dto.prompt.UserPromptResponse;
import com.jurong.aicenter.exception.BusinessException;
import com.jurong.aicenter.exception.ErrorCode;
import com.jurong.aicenter.security.JwtAuthenticationFilter.AuthenticatedUser;
import com.jurong.aicenter.service.UserPromptService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 用户提示词控制器
 */
@Slf4j
@RestController
@RequestMapping("/api/prompts")
@RequiredArgsConstructor
public class UserPromptController {

    private final UserPromptService userPromptService;

    /**
     * 保存提示词
     * 如果已存在相同提示词，则使用次数+1
     */
    @PostMapping
    public UserPromptResponse savePrompt(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @Valid @RequestBody SavePromptRequest request) {
        if (principal == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "请先登录");
        }
        String email = principal.email();
        log.info("保存提示词: email={}, promptLen={}", email, request.getPrompt().length());
        return userPromptService.savePrompt(email, request.getPrompt());
    }

    /**
     * 查询当前用户的所有提示词，按使用次数降序排列
     */
    @GetMapping
    public List<UserPromptResponse> listPrompts(
            @AuthenticationPrincipal AuthenticatedUser principal) {
        if (principal == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "请先登录");
        }
        String email = principal.email();
        log.info("查询提示词列表: email={}", email);
        return userPromptService.listByEmail(email);
    }

    /**
     * 使用提示词时调用，使用次数+1
     */
    @PutMapping("/{id}/use")
    public Map<String, Object> usePrompt(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable Long id) {
        if (principal == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "请先登录");
        }
        userPromptService.incrementUseCount(id);
        return Map.of("success", true);
    }

    /**
     * 删除提示词
     */
    @DeleteMapping("/{id}")
    public Map<String, Object> deletePrompt(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable Long id) {
        if (principal == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "请先登录");
        }
        userPromptService.deletePrompt(id, principal.email());
        return Map.of("success", true);
    }
}
