package com.jurong.aicenter.dto.agent;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class AgentSendResponse {
    private String sessionId;
    private String userMessageId;
    private String assistantMessageId;
    private Integer creditsUsed;
    private Integer creditsEstimated;

    /**
     * 工具调用（当 LLM 决定要跳转生成模块时填）。
     * 前端收到后展示 ConfirmDialog，用户确认后跳转到对应模块。
     */
    private ToolCall toolCall;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ToolCall {
        /** 动作名（jump-to-image / jump-to-video / ...） */
        private String action;
        /** 用户意图文本 */
        private String prompt;
        /** AI 简短解释（可空） */
        private String reason;
        /** 携带的图片素材 ID（前端跳转时作为 query 参数） */
        private List<String> attachmentIds;
    }
}
