package com.jurong.aicenter.service;

import com.jurong.aicenter.dto.agent.*;
import com.jurong.aicenter.entity.AgentSession;

import java.util.List;
import java.util.Map;

public interface AgentService {
    Map<String, Object> listSessions(Long userId, Integer page, Integer pageSize);

    AgentSession createSession(Long userId, String title);

    AgentSession rename(Long userId, String sessionId, String title);

    void deleteSession(Long userId, String sessionId);

    Map<String, Object> listMessages(Long userId, String sessionId, Integer page, Integer pageSize);

    AgentSendResponse send(Long userId, AgentSendRequest req);

    /**
     * 2026-08-14 新增:流式 send。每生成一段 token 就调 onToken,完整后自动存 DB。
     *   用于 /api/agent/send/stream 端点(SseEmitter)。
     */
    void sendStream(Long userId, AgentSendRequest req, java.util.function.Consumer<String> onToken);

    AgentCreditInfo getCredits(Long userId);

    CreditsCheckResponse checkCredits(Long userId, CreditsCheckRequest req);

    List<PlanInfo> listPlans();

    CreatePlanOrderResponse createPlanOrder(Long userId, CreatePlanOrderRequest req);

    QueryOrderResponse queryOrder(Long userId, String orderId);

    void cancelPlanOrder(Long userId, String orderId);

    ContactInfoResponse getContactInfo(String scope);

    List<CreditPackage> listCreditPackages();

    CreateCreditsOrderResponse createCreditsOrder(Long userId, CreateCreditsOrderRequest req);

    RedeemCardResponse redeemCard(Long userId, RedeemCardRequest req);
}
