package com.jurong.aicenter.controller;

import com.jurong.aicenter.dto.agent.*;
import com.jurong.aicenter.entity.AgentSession;
import com.jurong.aicenter.exception.BusinessException;
import com.jurong.aicenter.exception.ErrorCode;
import com.jurong.aicenter.security.JwtAuthenticationFilter.AuthenticatedUser;
import com.jurong.aicenter.service.AgentService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Agent REST API.
 */
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

    @PostMapping("/credits/check")
    public CreditsCheckResponse checkCredits(
            @AuthenticationPrincipal AuthenticatedUser user,
            @RequestBody(required = false) CreditsCheckRequest req) {
        requireUser(user);
        return agentService.checkCredits(user.id(), req);
    }

    @GetMapping("/plans")
    public List<PlanInfo> listPlans(@AuthenticationPrincipal AuthenticatedUser user) {
        requireUser(user);
        return agentService.listPlans();
    }

    @PostMapping("/plans/orders")
    public CreatePlanOrderResponse createPlanOrder(
            @AuthenticationPrincipal AuthenticatedUser user,
            @Valid @RequestBody CreatePlanOrderRequest req) {
        requireUser(user);
        return agentService.createPlanOrder(user.id(), req);
    }

    @GetMapping("/plans/orders/{orderId}")
    public QueryOrderResponse queryPlanOrder(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable String orderId) {
        requireUser(user);
        return agentService.queryOrder(user.id(), orderId);
    }

    @PostMapping("/plans/orders/{orderId}/cancel")
    public ResponseEntity<Void> cancelPlanOrder(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable String orderId) {
        requireUser(user);
        agentService.cancelPlanOrder(user.id(), orderId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/contact")
    public ContactInfoResponse getContactInfo(
            @AuthenticationPrincipal AuthenticatedUser user,
            @RequestParam(required = false) String scope) {
        requireUser(user);
        return agentService.getContactInfo(scope);
    }

    @GetMapping("/credits/packages")
    public List<CreditPackage> listCreditPackages(@AuthenticationPrincipal AuthenticatedUser user) {
        requireUser(user);
        return agentService.listCreditPackages();
    }

    @PostMapping("/credits/orders")
    public CreateCreditsOrderResponse createCreditsOrder(
            @AuthenticationPrincipal AuthenticatedUser user,
            @Valid @RequestBody CreateCreditsOrderRequest req) {
        requireUser(user);
        return agentService.createCreditsOrder(user.id(), req);
    }

    @PostMapping("/credits/redeem")
    public RedeemCardResponse redeemCard(
            @AuthenticationPrincipal AuthenticatedUser user,
            @Valid @RequestBody RedeemCardRequest req) {
        requireUser(user);
        return agentService.redeemCard(user.id(), req);
    }

    private void requireUser(AuthenticatedUser user) {
        if (user == null || user.id() == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
    }
}
