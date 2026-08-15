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
import org.springframework.http.MediaType;
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
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * Agent REST API.
 */
@RestController
@RequestMapping("/api/agent")
@RequiredArgsConstructor
@Slf4j
public class AgentController {

    private final AgentService agentService;

    /**
     * 2026-08-14 新增:流式 send 端点。
     *   - 用 SseEmitter 把 Service 的 onToken 回调包装成 SSE 响应
     *   - 用新线程跑 sendStream 避免阻塞 Spring MVC 线程池
     *   - 暂不支持多模态(带图走原 /send)
     */
    @PostMapping(value = "/send/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter sendStream(
            @AuthenticationPrincipal AuthenticatedUser user,
            @Valid @RequestBody AgentSendRequest req) {
        requireUser(user);
        SseEmitter emitter = new SseEmitter(180_000L);  // 3 分钟超时

        // 异步跑 sendStream,避免阻塞 Tomcat 线程
        // 2026-08-14 修复:token 回调里 emitter.send 抛 IOException 时,不要再 throw RuntimeException
        //   - 之前的逻辑会让 sendStream 提前中断(中断在某个 token 中间,后续 token 不发)
        //   - 且 RuntimeException 冒泡到 sendStream 还会触发 [META] 跳过 → 客户端等不到 done 事件
        //   - 表现:浏览器收到 ERR_INCOMPLETE_CHUNKED_ENCODING,一直"正在思考"
        //   - 现在:send 失败就 completeWithError,让 sendStream 后续代码尽量走完
        // 2026-08-14 二次修复:客户端断开(emitter.send 抛 IOException)不要用 completeWithError
        //   - completeWithError 让 Tomcat AsyncContext 进入 error state,触发 ERROR dispatch
        //   - ERROR dispatch 走 Security filter chain,AuthorizationFilter 抛 AccessDeniedException
        //   - 响应已 committed(Spring 写过 data: 帧),ExceptionTranslationFilter 写 403 失败
        //   - 表现:浏览器显示 ERR_INCOMPLETE_CHUNKED_ENCODING,一直"正在思考"
        //   - 客户端断开是正常情况,用 complete() 即可
        java.util.concurrent.atomic.AtomicBoolean clientGone = new java.util.concurrent.atomic.AtomicBoolean(false);
        new Thread(() -> {
            try {
                agentService.sendStream(user.id(), req, token -> {
                    if (clientGone.get()) return;  // 客户端已断开,跳过
                    try {
                        emitter.send(SseEmitter.event().data(token));
                    } catch (IOException e) {
                        // 客户端断开。标记一下,不要让后续 send 再抛,也不要中断 sendStream
                        clientGone.set(true);
                        log.info("SSE client disconnected mid-stream: {}", e.getMessage());
                        // 2026-08-14:不要 completeWithError,会触发 ERROR dispatch → Security 抛 AccessDeniedException
                        //   用 complete() 正常结束,前端 reader 收到 done,流式生命周期自然结束
                        emitter.complete();
                    }
                });
                if (!clientGone.get()) {
                    emitter.complete();
                }
            } catch (Exception e) {
                log.error("SSE sendStream failed: {}", e.getMessage());
                if (!clientGone.get()) {
                    // 2026-08-14:这里也是业务异常,会触发 ERROR dispatch
                    //   之前 Security 没放过 ERROR dispatch → AccessDeniedException → 响应中断
                    //   现在 SecurityConfig 已经把 ERROR dispatch 加入 permitAll,可以保留 completeWithError
                    //   但为了安全起见,业务异常也用 complete()(避免触发任何 ERROR dispatch)
                    //   前端正常收到 done,思考气泡如果 meta 为 null 会进 else 分支兜底
                    emitter.complete();
                }
            }
        }, "agent-send-stream-" + System.currentTimeMillis()).start();

        emitter.onTimeout(() -> {
            log.warn("SSE sendStream timeout (180s)");
            emitter.complete();
        });
        emitter.onCompletion(() -> log.debug("SSE sendStream completed"));
        return emitter;
    }

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
