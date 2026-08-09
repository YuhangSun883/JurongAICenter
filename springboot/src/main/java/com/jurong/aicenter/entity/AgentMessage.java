package com.jurong.aicenter.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Agent 对话消息。
 *
 * role: user / assistant / system
 * sessionId: 关联 AgentSession.id
 */
@Data
@TableName("agent_messages")
public class AgentMessage {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    private String sessionId;

    private Long userId;

    private String role;

    private String content;

    /** JSON 字符串（附件列表） */
    private String attachments;

    /** JSON 字符串（工具调用列表） */
    private String toolCalls;

    private String errorMessage;

    private LocalDateTime createdAt;
}
