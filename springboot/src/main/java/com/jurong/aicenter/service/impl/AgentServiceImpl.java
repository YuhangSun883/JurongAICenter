package com.jurong.aicenter.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.jurong.aicenter.client.NewApiClient;
import com.jurong.aicenter.dto.agent.*;
import com.jurong.aicenter.entity.AgentMessage;
import com.jurong.aicenter.entity.AgentSession;
import com.jurong.aicenter.entity.User;
import com.jurong.aicenter.exception.BusinessException;
import com.jurong.aicenter.exception.ErrorCode;
import com.jurong.aicenter.repository.AgentMessageRepository;
import com.jurong.aicenter.repository.AgentSessionRepository;
import com.jurong.aicenter.repository.UserRepository;
import com.jurong.aicenter.service.AgentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Agent 对话服务实现。
 *
 * 核心功能：会话 CRUD + 消息记忆 + 调 LLM。
 *
 * 关键约束（用户要求）：
 *   - session 与 session **互相独立，没有关联**
 *   - 每个 session 内消息按 created_at 升序（对话记忆）
 *   - 调 LLM 时按 session 内全部历史消息发（标准 chat/completions 格式）
 *   - 标题可以修改（rename）
 *   - 删除会话 CASCADE 删除消息
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentServiceImpl implements AgentService {

    private final AgentSessionRepository sessionRepo;
    private final AgentMessageRepository messageRepo;
    private final UserRepository userRepo;
    private final NewApiClient newApiClient;

    @Value("${llm.model:deepseek-v4-flash}")
    private String llmModel;

    @Value("${llm.max-tokens:1024}")
    private int llmMaxTokens;

    @Value("${llm.system-prompt:你是 Jurong AI 助手，简洁专业地回答用户问题。}")
    private String llmSystemPrompt;

    /** 默认每条消息消耗 1 积分（与前端估算口径一致） */
    private static final int DEFAULT_CREDITS_PER_MESSAGE = 1;

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
        log.info("Agent session created: id={}, userId={}", s.getId(), userId);
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
        // 先删消息（FK 关系不严谨，手动 CASCADE）
        messageRepo.delete(new QueryWrapper<AgentMessage>().eq("session_id", sessionId));
        sessionRepo.deleteById(sessionId);
        log.info("Agent session deleted: id={}, userId={}", sessionId, userId);
    }

    @Override
    public Map<String, Object> listMessages(Long userId, String sessionId, Integer page, Integer pageSize) {
        // 校验所有权
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
        // 1) 找到或创建 session
        AgentSession session;
        boolean isNewSession = false;
        if (req.getSessionId() == null || req.getSessionId().isBlank()) {
            // 用用户消息前 30 字做默认标题
            String defaultTitle = req.getContent().trim();
            if (defaultTitle.length() > 30) defaultTitle = defaultTitle.substring(0, 30) + "...";
            session = createSession(userId, defaultTitle);
            isNewSession = true;
        } else {
            session = requireOwnedSession(userId, req.getSessionId());
        }

        // 2) 存用户消息
        AgentMessage userMsg = new AgentMessage();
        userMsg.setSessionId(session.getId());
        userMsg.setUserId(userId);
        userMsg.setRole("user");
        userMsg.setContent(req.getContent());
        userMsg.setCreatedAt(LocalDateTime.now());
        messageRepo.insert(userMsg);

        // 3) 拉历史消息（仅当前 session），组装 chat messages
        List<AgentMessage> history = messageRepo.selectList(
            new QueryWrapper<AgentMessage>()
                .eq("session_id", session.getId())
                .orderByAsc("created_at")
        );

        // 4) 调 LLM
        String assistantContent = callLlm(history);

        // 5) 存助手回复
        AgentMessage assistantMsg = new AgentMessage();
        assistantMsg.setSessionId(session.getId());
        assistantMsg.setUserId(userId);
        assistantMsg.setRole("assistant");
        assistantMsg.setContent(assistantContent);
        assistantMsg.setCreatedAt(LocalDateTime.now());
        messageRepo.insert(assistantMsg);

        // 6) 更新 session 元数据
        int creditsUsed = (session.getCreditsUsed() == null ? 0 : session.getCreditsUsed()) + DEFAULT_CREDITS_PER_MESSAGE;
        session.setCreditsUsed(creditsUsed);
        session.setUpdatedAt(LocalDateTime.now());
        sessionRepo.updateById(session);

        log.info("Agent send OK: sessionId={}, userId={}, historyMsgs={}, responseLen={}",
            session.getId(), userId, history.size(), assistantContent.length());

        return new AgentSendResponse(
            session.getId(),
            userMsg.getId(),
            assistantMsg.getId(),
            creditsUsed,
            DEFAULT_CREDITS_PER_MESSAGE
        );
    }

    @Override
    public AgentCreditInfo getCredits(Long userId) {
        User user = userRepo.selectById(userId);
        if (user == null) {
            throw new BusinessException(ErrorCode.USER_NOT_FOUND, "用户不存在");
        }
        int remaining = user.getMonthlyQuota() == null ? 0 : (user.getMonthlyQuota() - (user.getQuotaUsed() == null ? 0 : user.getQuotaUsed()));
        return new AgentCreditInfo(
            user.getMonthlyQuota() == null ? 0 : user.getMonthlyQuota(),
            user.getQuotaUsed() == null ? 0 : user.getQuotaUsed(),
            Math.max(0, remaining),
            user.getMonthlyQuota() == null ? 0 : user.getMonthlyQuota(),
            null
        );
    }

    /* ================== helpers ================== */

    private AgentSession requireOwnedSession(Long userId, String sessionId) {
        AgentSession s = sessionRepo.selectById(sessionId);
        if (s == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "会话不存在");
        }
        if (!s.getUserId().equals(userId)) {
            // 不告诉前端"不存在"还是"无权限"，统一脱敏
            throw new BusinessException(ErrorCode.NOT_FOUND, "会话不存在");
        }
        return s;
    }

    /**
     * 调 LLM（一次性 chat completion，把整个 session 历史 + 当前用户消息发过去）
     * 注意：当前消息已写入 history（按 created_at 排序），无需重复传
     */
    private String callLlm(List<AgentMessage> history) {
        // 构造 messages 数组
        List<Map<String, String>> messages = new java.util.ArrayList<>();
        for (AgentMessage m : history) {
            String role = m.getRole();
            if (!"user".equals(role) && !"assistant".equals(role) && !"system".equals(role)) continue;
            Map<String, String> msg = new HashMap<>();
            msg.put("role", role);
            msg.put("content", m.getContent());
            messages.add(msg);
        }

        // 把整段历史拼成 user prompt（兼容 NewAPI chat 接口）
        // 因为 NewAPI 只接受单条 user content，我们把 system + 历史组装成一个文本
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
            String reply = newApiClient.chatCompletion(llmModel, llmSystemPrompt, userPrompt, llmMaxTokens);
            return reply;
        } catch (Exception e) {
            log.error("LLM call failed: {}", e.getMessage());
            return "抱歉，AI 助手暂时无法回复，请稍后再试。";
        }
    }
}
