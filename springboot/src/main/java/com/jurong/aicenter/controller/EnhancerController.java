package com.jurong.aicenter.controller;

import com.jurong.aicenter.dto.enhancer.EnhancerJobResponse;
import com.jurong.aicenter.dto.enhancer.EnhancerSubmitRequest;
import com.jurong.aicenter.dto.enhancer.EnhancerSubmitResponse;
import com.jurong.aicenter.exception.BusinessException;
import com.jurong.aicenter.exception.ErrorCode;
import com.jurong.aicenter.security.JwtAuthenticationFilter.AuthenticatedUser;
import com.jurong.aicenter.service.enhancer.EnhancerService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/**
 * 2026-08-15 新增:画质增强 Controller
 *
 * <p>端点:
 * <ul>
 *   <li>POST /api/image-enhancer/submit — 提交任务,返回 taskId</li>
 *   <li>GET  /api/image-enhancer/jobs/{taskId} — 轮询任务状态</li>
 * </ul>
 *
 * <p>前端工作台在选择完视频后调 submit,然后每 3-5 秒调一次 getJob 直到 status 变成终态。</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/image-enhancer")
@RequiredArgsConstructor
public class EnhancerController {

    private final EnhancerService enhancerService;

    @PostMapping("/submit")
    public EnhancerSubmitResponse submit(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @RequestBody EnhancerSubmitRequest request) {
        if (principal == null) throw new BusinessException(ErrorCode.UNAUTHORIZED);
        if (request == null) {
            throw new BusinessException(ErrorCode.INVALID_PARAM, "request body 不能为空");
        }
        log.info("[EnhancerController] submit: userId={}, videoUrl={}, version={}, setting={}",
            principal.id(), request.getVideoUrl(), request.getVersion(), request.getSetting());
        return enhancerService.submitWithOwner(principal.id(), request);
    }

    @GetMapping("/jobs/{taskId}")
    public EnhancerJobResponse getJob(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable String taskId) {
        if (principal == null) throw new BusinessException(ErrorCode.UNAUTHORIZED);
        return enhancerService.getJob(taskId, principal.id());
    }
}
