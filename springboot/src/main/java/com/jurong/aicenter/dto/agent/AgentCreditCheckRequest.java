package com.jurong.aicenter.dto.agent;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 积分前置校验请求（发送前调用）：
 * - 后端根据 action + estimated + context 精算 required
 * - 返回 ok：可直接调 send
 * - 返回 insufficient：弹充值弹窗
 */
@Data
public class AgentCreditCheckRequest {

    /** 本次操作需要的动作类型 */
    @NotBlank
    private String action;

    /** 这次要消耗多少（前端粗算；后端为准） */
    @NotNull
    private Integer estimated;

    /** 影响的参数：例如消息长度、是否带工具、模型等 */
    private java.util.Map<String, Object> context;
}