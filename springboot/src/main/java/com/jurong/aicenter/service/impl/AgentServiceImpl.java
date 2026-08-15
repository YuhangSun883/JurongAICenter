package com.jurong.aicenter.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.jurong.aicenter.client.NewApiClient;
import com.jurong.aicenter.dto.agent.*;
import com.jurong.aicenter.entity.AgentMessage;
import com.jurong.aicenter.entity.AgentSession;
import com.jurong.aicenter.entity.BillingLog;
import com.jurong.aicenter.entity.User;
import com.jurong.aicenter.exception.BusinessException;
import com.jurong.aicenter.exception.ErrorCode;
import com.jurong.aicenter.repository.AgentMessageRepository;
import com.jurong.aicenter.repository.AgentSessionRepository;
import com.jurong.aicenter.repository.BillingLogRepository;
import com.jurong.aicenter.repository.UserRepository;
import com.jurong.aicenter.service.AgentService;
import com.jurong.aicenter.service.MediaService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class AgentServiceImpl implements AgentService {

    private static final List<PlanInfo> PLANS = List.of(
            new PlanInfo("basic", "基础版（月卡）", "9.6 折", 99, 103, "适合轻度体验", 103, 30,
                    List.of(
                            "视频模型 Seedance 2.0 VIP 通道",
                            "高级图片模型",
                            "智能编辑，一键商照，无限画布",
                            "电脑/手机多端可用"
                    ),
                    false,
                    "ghost"),
            new PlanInfo("standard", "标准版（月卡）", "9 折", 399, 443, "更划算，适合持续创作", 443, 30,
                    List.of(
                            "视频模型 Seedance 2.0 VIP 通道",
                            "高级图片模型",
                            "智能编辑，一键商照，无限画布",
                            "电脑/手机多端可用"
                    ),
                    true,
                    "primary"),
            new PlanInfo("premium", "高级版（月卡）", "8.3 折", 699, 838, "单价更低，适合高强度创作", 838, 30,
                    List.of(
                            "视频模型 Seedance 2.0 VIP 通道",
                            "高级图片模型",
                            "智能编辑，一键商照，无限画布",
                            "电脑/手机多端可用"
                    ),
                    false,
                    "ghost"),
            new PlanInfo("enterprise", "企业套餐", null, 0, null, "联系客服", 0, 0,
                    List.of(
                            "企业用量与能力可单独报价",
                            "支持合同、对公与开票",
                            "提供企业内训和业务陪跑",
                            "资产存储数量与权限可按需定制"
                    ),
                    false,
                    "contact")
    );

    private static final List<CreditPackage> CREDIT_PACKAGES = List.of(
            new CreditPackage("pkg-50", 50, 50, false),
            new CreditPackage("pkg-75", 75, 75, true),
            new CreditPackage("pkg-150", 150, 150, false),
            new CreditPackage("pkg-225", 225, 225, false),
            new CreditPackage("pkg-450", 450, 450, false),
            new CreditPackage("pkg-882", 882, 900, false),
            new CreditPackage("pkg-1960", 1960, 2000, false),
            new CreditPackage("pkg-4900", 4900, 5000, false),
            new CreditPackage("pkg-9800", 9800, 10000, false)
    );

    private static final Map<String, OrderRecord> ORDER_STORE = new ConcurrentHashMap<>();
    private static final int DEFAULT_CREDITS_PER_MESSAGE = 1;
    private static final int AUTO_PAID_AFTER_MS = 3500;

    private final AgentSessionRepository sessionRepo;
    private final AgentMessageRepository messageRepo;
    private final UserRepository userRepo;
    private final BillingLogRepository billingLogRepository;
    private final NewApiClient newApiClient;
    private final MediaService mediaService;

    @Value("${llm.model:deepseek-v4-flash}")
    private String llmModel;

    @Value("${llm.max-tokens:1024}")
    private int llmMaxTokens;

    @Value("${llm.system-prompt:你是 Jurong AI 助手，简洁专业地回答用户问题。}")
    private String llmSystemPrompt;

    @Override
    public Map<String, Object> listSessions(Long userId, Integer page, Integer pageSize) {
        int p = page == null || page < 1 ? 1 : page;
        int ps = pageSize == null || pageSize < 1 ? 50 : pageSize;

        QueryWrapper<AgentSession> qw = new QueryWrapper<>();
        qw.eq("user_id", userId);
        qw.orderByDesc("pinned", "updated_at");
        qw.last("LIMIT " + ps + " OFFSET " + ((p - 1) * ps));

        List<AgentSession> list = sessionRepo.selectList(qw);
        Long total = sessionRepo.selectCount(new QueryWrapper<AgentSession>().eq("user_id", userId));

        Map<String, Object> result = new HashMap<>();
        result.put("items", list.stream().map(AgentSessionDto::from).collect(Collectors.toList()));
        result.put("total", total);
        result.put("page", p);
        result.put("pageSize", ps);
        return result;
    }

    @Override
    public AgentSession createSession(Long userId, String title) {
        AgentSession s = new AgentSession();
        s.setUserId(userId);
        s.setTitle(title == null || title.isBlank() ? "新对话" : title.trim());
        s.setPinned(false);
        s.setCreditsUsed(0);
        s.setStatus("active");
        s.setCreatedAt(LocalDateTime.now());
        s.setUpdatedAt(LocalDateTime.now());
        sessionRepo.insert(s);
        return s;
    }

    @Override
    public AgentSession rename(Long userId, String sessionId, String title) {
        AgentSession s = requireOwnedSession(userId, sessionId);
        s.setTitle(title.trim());
        s.setUpdatedAt(LocalDateTime.now());
        sessionRepo.updateById(s);
        return s;
    }

    @Override
    @Transactional
    public void deleteSession(Long userId, String sessionId) {
        requireOwnedSession(userId, sessionId);
        messageRepo.delete(new QueryWrapper<AgentMessage>().eq("session_id", sessionId));
        sessionRepo.deleteById(sessionId);
    }

    @Override
    public Map<String, Object> listMessages(Long userId, String sessionId, Integer page, Integer pageSize) {
        requireOwnedSession(userId, sessionId);

        int p = page == null || page < 1 ? 1 : page;
        int ps = pageSize == null || pageSize < 1 ? 50 : pageSize;

        QueryWrapper<AgentMessage> qw = new QueryWrapper<>();
        qw.eq("session_id", sessionId);
        qw.orderByAsc("created_at");
        qw.last("LIMIT " + ps + " OFFSET " + ((p - 1) * ps));

        List<AgentMessage> list = messageRepo.selectList(qw);
        Long total = messageRepo.selectCount(new QueryWrapper<AgentMessage>().eq("session_id", sessionId));

        Map<String, Object> result = new HashMap<>();
        result.put("items", list.stream().map(AgentMessageDto::from).collect(Collectors.toList()));
        result.put("total", total);
        result.put("page", p);
        result.put("pageSize", ps);
        return result;
    }

    @Override
    @Transactional
    public AgentSendResponse send(Long userId, AgentSendRequest req) {
        // 1) 找/建 session
        AgentSession session;
        if (req.getSessionId() == null || req.getSessionId().isBlank()) {
            String defaultTitle = req.getContent().trim();
            if (defaultTitle.length() > 30) {
                defaultTitle = defaultTitle.substring(0, 30) + "...";
            }
            session = createSession(userId, defaultTitle);
        } else {
            session = requireOwnedSession(userId, req.getSessionId());
        }

        // 2) 把 attachmentIds 转成图片 URL（多模态用）+ 完整素材信息（前端显示用）
        List<String> imageUrls = mediaService.getImageUrlsByIds(
            userId, req.getAttachmentIds()
        );
        List<Map<String, Object>> attachmentInfos = new java.util.ArrayList<>();
        if (req.getAttachmentIds() != null && !req.getAttachmentIds().isEmpty()) {
            // 拉素材详情（含 url/name/type）
            List<com.jurong.aicenter.entity.MediaAsset> assets =
                mediaService.getAssetsByIds(userId, req.getAttachmentIds());
            for (com.jurong.aicenter.entity.MediaAsset a : assets) {
                Map<String, Object> m = new java.util.HashMap<>();
                m.put("id", String.valueOf(a.getId()));
                m.put("type", a.getType());
                m.put("name", a.getName());
                m.put("url", a.getObjectKey() == null
                    ? null
                    : mediaService.getPresignedUrl(a.getObjectKey(), 24));
                attachmentInfos.add(m);
            }
        }

        // 3) 存用户消息（含 attachments JSON 数组）
        AgentMessage userMsg = new AgentMessage();
        userMsg.setSessionId(session.getId());
        userMsg.setUserId(userId);
        userMsg.setRole("user");
        userMsg.setContent(req.getContent());
        if (!attachmentInfos.isEmpty()) {
            // 存完整的 attachment 信息（含 url/name/type），前端消息气泡显示图片用
            try {
                userMsg.setAttachments(new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(attachmentInfos));
            } catch (Exception ignore) {
                userMsg.setAttachments(null);
            }
        }
        userMsg.setCreatedAt(LocalDateTime.now());
        messageRepo.insert(userMsg);

        // 4) 拉历史消息
        List<AgentMessage> history = messageRepo.selectList(
                new QueryWrapper<AgentMessage>()
                        .eq("session_id", session.getId())
                        .orderByAsc("created_at")
        );

        // 5) 调 LLM（多模态 vs 文字）
        String llmReply;
        try {
            if (imageUrls.isEmpty()) {
                llmReply = callLlmTextOnly(history);
            } else {
                llmReply = newApiClient.chatCompletionWithImages(
                    llmModel, llmSystemPrompt, req.getContent(),
                    imageUrls, llmMaxTokens
                );
            }
        } catch (Exception e) {
            log.error("Agent LLM call failed: {}", e.getMessage());
            llmReply = "抱歉，AI 助手暂时无法回复，请稍后再试。";
        }

        // 6) 解析 tool_call JSON，提取显示内容和工具调用
        ParsedLlmReply parsed = parseLlmReply(llmReply);

        // 7) 存 assistant 消息
        AgentMessage assistantMsg = new AgentMessage();
        assistantMsg.setSessionId(session.getId());
        assistantMsg.setUserId(userId);
        assistantMsg.setRole("assistant");
        assistantMsg.setContent(parsed.displayContent);
        if (parsed.toolCallJson != null) {
            assistantMsg.setToolCalls(parsed.toolCallJson);
        }
        assistantMsg.setCreatedAt(LocalDateTime.now());
        messageRepo.insert(assistantMsg);

        // 8) 更新 session 积分
        int creditsUsed = (session.getCreditsUsed() == null ? 0 : session.getCreditsUsed()) + DEFAULT_CREDITS_PER_MESSAGE;
        session.setCreditsUsed(creditsUsed);
        session.setUpdatedAt(LocalDateTime.now());
        sessionRepo.updateById(session);

        // 9) 构造响应
        AgentSendResponse resp = new AgentSendResponse(
                session.getId(),
                userMsg.getId(),
                assistantMsg.getId(),
                creditsUsed,
                DEFAULT_CREDITS_PER_MESSAGE,
                null
        );

        // 10) 如果有 tool_call，附在响应里
        if (parsed.toolCall != null) {
            AgentSendResponse.ToolCall tc = new AgentSendResponse.ToolCall();
            tc.setAction(parsed.toolCall.getAction());
            tc.setPrompt(parsed.toolCall.getPrompt());
            tc.setReason(parsed.toolCall.getReason());
            // 携带用户上传的素材 ID（前端跳转时用）
            tc.setAttachmentIds(req.getAttachmentIds());
            resp.setToolCall(tc);
            log.info("Agent toolCall detected: action={}, prompt={}, attachments={}",
                tc.getAction(), tc.getPrompt(), tc.getAttachmentIds() == null ? 0 : tc.getAttachmentIds().size());
        }

        return resp;
    }

    /** 内部：LLM 回复解析结果（显示文本 + 工具调用） */
    private static class ParsedLlmReply {
        String displayContent;     // 显示给用户的纯文本
        String toolCallJson;       // 原始 tool_call JSON（存 DB）
        AgentSendResponse.ToolCall toolCall;  // 解析后的对象
    }

    /** 解析 LLM 回复，提取 tool_call JSON（如果存在） */
    private ParsedLlmReply parseLlmReply(String reply) {
        ParsedLlmReply result = new ParsedLlmReply();
        if (reply == null) {
            result.displayContent = "";
            return result;
        }
        // 提取 {"tool_call": {...}} 部分（支持嵌套花括号）
        java.util.regex.Pattern p = java.util.regex.Pattern.compile(
            "\\{\\s*\"tool_call\"\\s*:\\s*\\{(?:[^{}]|\\{[^{}]*\\})*\\}\\s*\\}",
            java.util.regex.Pattern.DOTALL
        );
        java.util.regex.Matcher m = p.matcher(reply);
        if (m.find()) {
            String toolJson = m.group();
            result.toolCallJson = toolJson;
            // 把 JSON 从 content 里剔除
            String before = reply.substring(0, m.start()).trim();
            String after = reply.substring(m.end()).trim();
            result.displayContent = (before + " " + after).trim();
            // 解析 JSON 拿 action/prompt/reason
            try {
                com.fasterxml.jackson.databind.ObjectMapper mapper =
                    new com.fasterxml.jackson.databind.ObjectMapper();
                com.fasterxml.jackson.databind.JsonNode node = mapper.readTree(toolJson);
                com.fasterxml.jackson.databind.JsonNode tc = node.get("tool_call");
                if (tc != null) {
                    AgentSendResponse.ToolCall t = new AgentSendResponse.ToolCall();
                    if (tc.has("action")) t.setAction(tc.get("action").asText());
                    if (tc.has("prompt")) t.setPrompt(tc.get("prompt").asText());
                    if (tc.has("reason")) t.setReason(tc.get("reason").asText());
                    result.toolCall = t;
                }
            } catch (Exception e) {
                log.warn("Failed to parse tool_call JSON: {}", e.getMessage());
            }
        } else {
            result.displayContent = reply.trim();
        }
        return result;
    }

    /**
     * 纯文字 LLM 调用（无图片时用，保持旧逻辑）。
     */
    private String callLlmTextOnly(List<AgentMessage> history) {
        // 构造 messages 数组
        List<Map<String, String>> messages = new ArrayList<>();
        for (AgentMessage m : history) {
            String role = m.getRole();
            if (!"user".equals(role) && !"assistant".equals(role) && !"system".equals(role)) continue;
            Map<String, String> msg = new HashMap<>();
            msg.put("role", role);
            msg.put("content", m.getContent());
            messages.add(msg);
        }

        StringBuilder sb = new StringBuilder();
        sb.append(llmSystemPrompt).append("\n\n");
        for (Map<String, String> m : messages) {
            String role = m.get("role");
            if ("system".equals(role)) continue;
            sb.append("[").append(role.toUpperCase()).append("]\n");
            sb.append(m.get("content")).append("\n\n");
        }
        sb.append("[ASSISTANT]\n");
        String userPrompt = sb.toString();

        try {
            return newApiClient.chatCompletion(llmModel, llmSystemPrompt, userPrompt, llmMaxTokens);
        } catch (Exception e) {
            log.error("LLM call failed: {}", e.getMessage());
            return "抱歉，AI 助手暂时无法回复，请稍后再试。";
        }
    }

    @Override
    public AgentCreditInfo getCredits(Long userId) {
        User user = requireUser(userId);
        int remaining = resolveRemainingCredits(user);
        return new AgentCreditInfo(
                user.getMonthlyQuota() == null ? 0 : user.getMonthlyQuota(),
                remaining,
                remaining,
                user.getMonthlyQuota() == null ? 0 : user.getMonthlyQuota(),
                null
        );
    }

    @Override
    public CreditLedgerResponse listCreditLedger(Long userId, String type, String tool, Integer page, Integer pageSize) {
        int currentPage = page == null || page < 1 ? 1 : page;
        int currentPageSize = pageSize == null || pageSize < 1 ? 20 : Math.min(pageSize, 100);
        String normalizedType = normalizeLedgerType(type);
        String normalizedTool = tool == null ? "" : tool.trim();

        QueryWrapper<BillingLog> wrapper = new QueryWrapper<>();
        wrapper.eq("user_id", userId);
        if (normalizedType != null) {
            wrapper.eq("type", normalizedType);
        }
        wrapper.orderByDesc("created_at");

        List<CreditLedgerItem> filtered = billingLogRepository.selectList(wrapper).stream()
                .filter(log -> matchesTool(log, normalizedTool))
                .map(this::toCreditLedgerItem)
                .toList();
        int from = Math.min((currentPage - 1) * currentPageSize, filtered.size());
        int to = Math.min(from + currentPageSize, filtered.size());
        return new CreditLedgerResponse(filtered.subList(from, to), filtered.size(), currentPage, currentPageSize);
    }

    @Override
    public CreditsCheckResponse checkCredits(Long userId, CreditsCheckRequest req) {
        User user = requireUser(userId);
        int remaining = resolveRemainingCredits(user);
        int required = resolveRequiredCredits(req);
        if (remaining < required) {
            return new CreditsCheckResponse(
                    "insufficient",
                    remaining,
                    required,
                    ErrorCode.QUOTA_INSUFFICIENT.getCode(),
                    ErrorCode.QUOTA_INSUFFICIENT.getMessage(),
                    null
            );
        }
        return new CreditsCheckResponse("ok", remaining, required, null, null, null);
    }

    @Override
    public List<PlanInfo> listPlans() {
        return PLANS;
    }

    @Override
    public CreatePlanOrderResponse createPlanOrder(Long userId, CreatePlanOrderRequest req) {
        PlanInfo plan = findPlan(req.getPlanId());
        String orderId = "order_" + randomId();
        String payMethod = normalizePayMethod(req.getPayMethod());
        long expireAt = System.currentTimeMillis() + 15 * 60 * 1000L;

        ORDER_STORE.put(orderId, new OrderRecord(
                orderId,
                userId,
                OrderKind.PLAN,
                plan.getId(),
                plan.getPrice(),
                plan.getCredits(),
                plan.getValidDays(),
                payMethod,
                expireAt
        ));

        return new CreatePlanOrderResponse(
                orderId,
                buildPayUrl(orderId),
                plan.getPrice(),
                buildQrCodeUrl(orderId, plan.getPrice()),
                buildQrCodeContent(orderId, plan.getPrice()),
                payMethod,
                expireAt,
                "pending"
        );
    }

    @Override
    @Transactional
    public QueryOrderResponse queryOrder(Long userId, String orderId) {
        OrderRecord order = ORDER_STORE.get(orderId);
        if (order == null || order.userId != userId) {
            return new QueryOrderResponse(orderId, "expired", null, null, 0, "", null);
        }

        long now = System.currentTimeMillis();
        if ("pending".equals(order.status)) {
            order.pollCount++;
            if (now >= order.expireAt) {
                order.status = "expired";
            } else if (now >= order.autoPaidAt || order.pollCount >= 2) {
                order.status = "paid";
                order.paidAt = now;
                applyOrderEffect(order, userId);
            }
        }

        QueryOrderResponse.Receipt receipt = null;
        if ("paid".equals(order.status)) {
            receipt = new QueryOrderResponse.Receipt(order.credits, order.validDays);
        }

        return new QueryOrderResponse(
                order.orderId,
                order.status,
                order.paidAt,
                null,
                order.amount,
                order.refId,
                receipt
        );
    }

    @Override
    public void cancelPlanOrder(Long userId, String orderId) {
        OrderRecord order = ORDER_STORE.get(orderId);
        if (order == null || order.userId != userId) {
            return;
        }
        if ("pending".equals(order.status)) {
            order.status = "cancelled";
        }
    }

    @Override
    public ContactInfoResponse getContactInfo(String scope) {
        if ("general".equalsIgnoreCase(scope)) {
            return new ContactInfoResponse(
                    "通用客服",
                    "如需咨询功能使用、账号问题或开票信息，请联系通用客服。",
                    List.of(
                            new ContactChannel("phone", null, null, "400-800-0000", "工作日 9:00-18:00"),
                            new ContactChannel("email", null, null, "support@jurong.ai", "24 小时内回复")
                    ),
                    "工作时间内优先电话联系。"
            );
        }

        return new ContactInfoResponse(
                "企业套餐咨询",
                "扫码添加客服，咨询企业充值与定制方案。",
                List.of(
                        new ContactChannel(
                                "wechat",
                                buildContactQrCode(),
                                null,
                                null,
                                "扫码添加，咨询充值"
                        )
                ),
                "扫码添加，咨询充值。"
        );
    }

    @Override
    public List<CreditPackage> listCreditPackages() {
        return CREDIT_PACKAGES;
    }

    @Override
    public CreateCreditsOrderResponse createCreditsOrder(Long userId, CreateCreditsOrderRequest req) {
        CreditPackage pkg = findCreditPackage(req.getPackageId());
        String orderId = "cord_" + randomId();
        String payMethod = normalizePayMethod(req.getPayMethod());
        long expireAt = System.currentTimeMillis() + 15 * 60 * 1000L;

        ORDER_STORE.put(orderId, new OrderRecord(
                orderId,
                userId,
                OrderKind.CREDIT,
                pkg.getId(),
                pkg.getPrice(),
                pkg.getCredits(),
                0,
                payMethod,
                expireAt
        ));

        return new CreateCreditsOrderResponse(
                orderId,
                buildPayUrl(orderId),
                pkg.getPrice(),
                pkg.getCredits(),
                buildQrCodeUrl(orderId, pkg.getPrice()),
                buildQrCodeContent(orderId, pkg.getPrice()),
                payMethod,
                expireAt,
                "pending"
        );
    }

    @Override
    @Transactional
    public RedeemCardResponse redeemCard(Long userId, RedeemCardRequest req) {
        String code = req.getCode() == null ? "" : req.getCode().trim().toUpperCase();
        if (!code.matches("^[A-Z]{2}-[A-Z0-9]{4}-[A-Z0-9]{4}-[A-Z0-9]{4}$")) {
            throw new BusinessException(ErrorCode.INVALID_PARAM, "卡密格式错误");
        }

        int creditsAdded = 100 + Math.abs(code.hashCode() % 901);
        String redeemId = "rc_" + randomId();
        int remaining = addCreditsToUser(userId, creditsAdded, "兑换充值卡", redeemId);
        return new RedeemCardResponse(
                creditsAdded,
                365,
                redeemId,
                remaining
        );
    }

    private void applyOrderEffect(OrderRecord order, Long userId) {
        if (order.applied.compareAndSet(false, true)) {
            String desc = order.kind == OrderKind.PLAN
                    ? "订阅套餐充值：" + order.refId
                    : "积分充值：" + order.refId;
            addCreditsToUser(userId, order.credits, desc, order.orderId);
        }
    }

    private int addCreditsToUser(Long userId, int delta) {
        return addCreditsToUser(userId, delta, "积分充值", null);
    }

    private int addCreditsToUser(Long userId, int delta, String description, String paymentId) {
        User user = requireUser(userId);
        int base = user.getCredits() != null
                ? Math.max(0, user.getCredits())
                : resolveRemainingCredits(user);
        int total = base + Math.max(0, delta);
        user.setCredits(total);
        user.setUpdatedAt(LocalDateTime.now());
        userRepo.updateById(user);

        BillingLog log = new BillingLog();
        log.setUserId(userId);
        log.setType("RECHARGE");
        log.setCreditsDelta(Math.max(0, delta));
        log.setBalanceAfter(total);
        log.setDescription(description == null || description.isBlank() ? "积分充值" : description);
        log.setPaymentId(paymentId);
        log.setCreatedAt(LocalDateTime.now());
        billingLogRepository.insert(log);
        return total;
    }

    private String normalizeLedgerType(String type) {
        if (type == null || type.isBlank() || "ALL".equalsIgnoreCase(type)) return null;
        return type.trim().toUpperCase();
    }

    private boolean matchesTool(BillingLog log, String tool) {
        if (tool == null || tool.isBlank() || "ALL".equalsIgnoreCase(tool)) return true;
        return inferTool(log).equalsIgnoreCase(tool.trim());
    }

    private CreditLedgerItem toCreditLedgerItem(BillingLog log) {
        return new CreditLedgerItem(
                log.getId(),
                log.getJobId(),
                log.getType(),
                log.getCreditsDelta(),
                log.getBalanceAfter(),
                log.getDescription(),
                log.getPaymentId(),
                inferTool(log),
                log.getCreatedAt()
        );
    }

    private String inferTool(BillingLog log) {
        String text = ((log.getDescription() == null ? "" : log.getDescription()) + " "
                + (log.getPaymentId() == null ? "" : log.getPaymentId())).toLowerCase();
        if (text.contains("video") || text.contains("视频")) return "video";
        if (text.contains("image") || text.contains("图片") || text.contains("画面")) return "image";
        if (text.contains("subtitle") || text.contains("字幕")) return "subtitle";
        if (text.contains("enhance") || text.contains("增强")) return "enhance";
        if (text.contains("prompt") || text.contains("提示词")) return "prompt";
        if (text.contains("agent") || text.contains("助手") || text.contains("对话")) return "agent";
        if (text.contains("订阅") || text.contains("套餐") || text.contains("充值") || text.contains("兑换")) return "recharge";
        return "other";
    }

    private User requireUser(Long userId) {
        User user = userRepo.selectById(userId);
        if (user == null) {
            throw new BusinessException(ErrorCode.USER_NOT_FOUND, "用户不存在");
        }
        return user;
    }

    private int resolveRemainingCredits(User user) {
        if (user.getCredits() != null) {
            return Math.max(0, user.getCredits());
        }
        int monthlyQuota = user.getMonthlyQuota() == null ? 0 : user.getMonthlyQuota();
        int used = user.getQuotaUsed() == null ? 0 : user.getQuotaUsed();
        return Math.max(0, monthlyQuota - used);
    }

    private int resolveRequiredCredits(CreditsCheckRequest req) {
        if (req != null && req.getEstimated() != null && req.getEstimated() > 0) {
            return req.getEstimated();
        }
        String action = req == null ? null : req.getAction();
        if ("video-create".equals(action)) {
            return 30;
        }
        if ("image-create".equals(action)) {
            return 10;
        }
        if ("agent-chat".equals(action) || "agent-send".equals(action)) {
            return DEFAULT_CREDITS_PER_MESSAGE;
        }
        return DEFAULT_CREDITS_PER_MESSAGE;
    }

    private AgentSession requireOwnedSession(Long userId, String sessionId) {
        AgentSession s = sessionRepo.selectById(sessionId);
        if (s == null || !s.getUserId().equals(userId)) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "会话不存在");
        }
        return s;
    }

    private PlanInfo findPlan(String planId) {
        return PLANS.stream()
                .filter(p -> p.getId().equals(planId))
                .findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "套餐不存在"));
    }

    private CreditPackage findCreditPackage(String packageId) {
        return CREDIT_PACKAGES.stream()
                .filter(p -> p.getId().equals(packageId))
                .findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "积分包不存在"));
    }

    private String normalizePayMethod(String payMethod) {
        if (payMethod == null || payMethod.isBlank()) {
            return "alipay";
        }
        String normalized = payMethod.trim().toLowerCase();
        if ("wechat".equals(normalized) || "alipay".equals(normalized) || "card".equals(normalized)) {
            return normalized;
        }
        return "alipay";
    }

    private String randomId() {
        return java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 10);
    }

    private String buildPayUrl(String orderId) {
        return "https://pay.example.com/checkout?order=" + orderId;
    }

    private String buildQrCodeUrl(String orderId, int amount) {
        return "https://api.qrserver.com/v1/create-qr-code/?size=240x240&data="
                + java.net.URLEncoder.encode("mock://pay?order=" + orderId + "&amount=" + amount, java.nio.charset.StandardCharsets.UTF_8);
    }

    private String buildQrCodeContent(String orderId, int amount) {
        return "mock://pay?order=" + orderId + "&amount=" + amount;
    }

    private String buildContactQrCode() {
        return "https://api.qrserver.com/v1/create-qr-code/?size=300x300&data="
                + java.net.URLEncoder.encode("weixin://contacts?username=jurong_kefu", java.nio.charset.StandardCharsets.UTF_8);
    }

    private String callLlm(List<AgentMessage> history) {
        List<Map<String, String>> messages = new ArrayList<>();
        for (AgentMessage m : history) {
            String role = m.getRole();
            if (!"user".equals(role) && !"assistant".equals(role) && !"system".equals(role)) {
                continue;
            }
            Map<String, String> msg = new HashMap<>();
            msg.put("role", role);
            msg.put("content", m.getContent());
            messages.add(msg);
        }

        StringBuilder sb = new StringBuilder();
        sb.append(llmSystemPrompt).append("\n\n");
        for (Map<String, String> m : messages) {
            String role = m.get("role");
            if ("system".equals(role)) {
                continue;
            }
            sb.append("[").append(role.toUpperCase()).append("]\n");
            sb.append(m.get("content")).append("\n\n");
        }
        sb.append("[ASSISTANT]\n");

        try {
            return newApiClient.chatCompletion(llmModel, llmSystemPrompt, sb.toString(), llmMaxTokens);
        } catch (Exception e) {
            log.error("LLM call failed: {}", e.getMessage());
            return "抱歉，AI 助手暂时无法回复，请稍后再试。";
        }
    }

    private enum OrderKind {
        PLAN,
        CREDIT
    }

    private static final class OrderRecord {
        private final String orderId;
        private final Long userId;
        private final OrderKind kind;
        private final String refId;
        private final int amount;
        private final int credits;
        private final int validDays;
        private final String payMethod;
        private final long expireAt;
        private final long autoPaidAt;
        private final AtomicBoolean applied = new AtomicBoolean(false);
        private volatile String status = "pending";
        private volatile Long paidAt;
        private volatile int pollCount;

        private OrderRecord(
                String orderId,
                Long userId,
                OrderKind kind,
                String refId,
                int amount,
                int credits,
                int validDays,
                String payMethod,
                long expireAt) {
            this.orderId = orderId;
            this.userId = userId;
            this.kind = kind;
            this.refId = refId;
            this.amount = amount;
            this.credits = credits;
            this.validDays = validDays;
            this.payMethod = payMethod;
            this.expireAt = expireAt;
            this.autoPaidAt = System.currentTimeMillis() + AUTO_PAID_AFTER_MS;
        }
    }
}
