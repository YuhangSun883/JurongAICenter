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

    AgentCreditInfo getCredits(Long userId);

    CreditLedgerResponse listCreditLedger(Long userId, String type, String tool, Integer page, Integer pageSize);

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
