package com.jurong.aicenter.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Agent 对话会话。
 *
 * 一个 session 代表一次连续对话，包含若干条 message；
 * session 与 session 之间 **互相独立，没有关联**（用户要求）。
 */
@Data
@TableName("agent_sessions")
public class AgentSession {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    private Long userId;

    private String title;

    private Boolean pinned;

    private Integer creditsUsed;

    /** active / archived */
    private String status;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
