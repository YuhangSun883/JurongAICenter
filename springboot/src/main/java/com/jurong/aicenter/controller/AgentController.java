package com.jurong.aicenter.controller;

import com.jurong.aicenter.dto.agent.*;
import com.jurong.aicenter.entity.AgentSession;
import com.jurong.aicenter.exception.BusinessException;
import com.jurong.aicenter.exception.ErrorCode;
import com.jurong.aicenter.security.JwtAuthenticationFilter.AuthenticatedUser;
import com.jurong.aicenter.service.AgentService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Agent 对话 REST API。
 *
 * 端点列表(MVP - 仅对话核心):
 *   GET    /api/agent/sessions                  拉会话列表
 *   POST   /api/agent/sessions                  新建会话
 *   PATCH  /api/agent/sessions/{id}             修改标题(重命名)
 *   DELETE /api/agent/sessions/{id}             删除会话(级联删除消息)
 *   GET    /api/agent/sessions/{id}/messages    拉某会话消息(对话记忆)
 *   POST   /api/agent/send                      发送消息 + 调 LLM
 *   GET    /api/agent/credits                   当前用户积分
 *
 * 关键约束:
 *   - 会话之间互相独立,没有关联
 *   - 必须鉴权 (Spring Security 已配 /api/agent/** = authenticated)
 */
@Slf4j
@RestController
@RequestMapping("/api/agent")
@RequiredArgsConstructor
public class AgentController {

    private final AgentService agentService;

    @GetMapping("/sessions")
    public Map<String, Object> listSessions(
            @AuthenticationPrincipal AuthenticatedUser user,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer pageSize) {
        requireUser(user);
        return agentService.listSessions(user.id(), page, pageSize);
    }

    @PostMapping("/sessions")
    public AgentCreateSessionResponse createSession(
            @AuthenticationPrincipal AuthenticatedUser user,
            @Valid @RequestBody(required = false) AgentCreateSessionRequest req) {
        requireUser(user);
        String title = req == null ? null : req.getTitle();
        AgentSession s = agentService.createSession(user.id(), title);
        return new AgentCreateSessionResponse(AgentSessionDto.from(s));
    }

    @PatchMapping("/sessions/{id}")
    public AgentSessionDto renameSession(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable String id,
            @Valid @RequestBody AgentRenameRequest req) {
        requireUser(user);
        AgentSession s = agentService.rename(user.id(), id, req.getTitle());
        return AgentSessionDto.from(s);
    }

    @DeleteMapping("/sessions/{id}")
    public Map<String, Object> deleteSession(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable String id) {
        requireUser(user);
        agentService.deleteSession(user.id(), id);
        return Map.of("sessionId", id, "status", "deleted");
    }

    @GetMapping("/sessions/{id}/messages")
    public Map<String, Object> listMessages(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable String id,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer pageSize) {
        requireUser(user);
        return agentService.listMessages(user.id(), id, page, pageSize);
    }

    @PostMapping("/send")
    public AgentSendResponse send(
            @AuthenticationPrincipal AuthenticatedUser user,
            @Valid @RequestBody AgentSendRequest req) {
        requireUser(user);
        return agentService.send(user.id(), req);
    }

    @GetMapping("/credits")
    public AgentCreditInfo getCredits(@AuthenticationPrincipal AuthenticatedUser user) {
        requireUser(user);
        return agentService.getCredits(user.id());
    }

    private void requireUser(AuthenticatedUser user) {
        if (user == null || user.id() == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
    }
}
