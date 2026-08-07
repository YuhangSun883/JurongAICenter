package com.jurong.aicenter.service;

import com.jurong.aicenter.dto.agent.*;
import com.jurong.aicenter.entity.AgentMessage;
import com.jurong.aicenter.entity.AgentSession;

import java.util.List;
import java.util.Map;

public interface AgentService {
    /** 列表查询会话 */
    Map<String, Object> listSessions(Long userId, Integer page, Integer pageSize);
    /** 创建会话 */
    AgentSession createSession(Long userId, String title);
    /** 重命名 */
    AgentSession rename(Long userId, String sessionId, String title);
    /** 删除（CASCADE 删除消息） */
    void deleteSession(Long userId, String sessionId);
    /** 拉消息 */
    Map<String, Object> listMessages(Long userId, String sessionId, Integer page, Integer pageSize);
    /** 发送消息 + 调 LLM + 存助手回复 */
    AgentSendResponse send(Long userId, AgentSendRequest req);
    /** 当前用户积分 */
    AgentCreditInfo getCredits(Long userId);
}
