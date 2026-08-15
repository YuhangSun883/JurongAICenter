package com.jurong.aicenter.dto.agent;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 积分前置校验响应。
 *
 * <p>前端契约：
 * <pre>
 * status: 'ok' | 'insufficient' | 'unknown'
 * remaining: number  // 剩余积分
 * required: number   // 本次操作实际需要（后端精算）
 * code?: number      // insufficient 时有
 * message?: string   // insufficient 时有
 * </pre>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AgentCreditCheckResponse {
    private String status;
    private Integer remaining;
    private Integer required;
    private Integer code;
    private String message;
}