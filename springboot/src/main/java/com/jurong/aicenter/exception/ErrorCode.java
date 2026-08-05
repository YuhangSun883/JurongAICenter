package com.jurong.aicenter.exception;

import lombok.Getter;

/**
 * 错误码分段约定：
 * Auth: 1xxx
 * User: 2xxx
 * Generation: 3xxx
 * Workflow: 4xxx
 * Billing: 5xxx（Phase 8）
 * Common: 9xxx
 */
@Getter
public enum ErrorCode {

    // Common (9xxx)
    SUCCESS(0, "success"),
    INTERNAL_ERROR(9999, "服务器内部错误"),
    INVALID_PARAM(9001, "参数无效"),
    UNAUTHORIZED(9401, "未登录"),
    FORBIDDEN(9403, "无权限"),
    NOT_FOUND(9404, "资源不存在"),

    // Auth (1xxx)
    EMAIL_ALREADY_EXISTS(1001, "邮箱已被注册"),
    INVALID_CREDENTIALS(1002, "邮箱或密码错误"),
    TOKEN_EXPIRED(1101, "Token 已过期"),
    INVALID_TOKEN(1102, "Token 无效"),

    // User (2xxx)
    USER_NOT_FOUND(2001, "用户不存在"),
    USER_DISABLED(2002, "用户已禁用"),

    // Generation (3xxx)
    WORKFLOW_INVALID(3001, "工作流 JSON 不合法"),
    COMFYUI_UNREACHABLE(3002, "ComfyUI 服务不可达"),
    COMFYUI_REJECTED(3003, "ComfyUI 拒绝请求"),
    QUOTA_INSUFFICIENT(3004, "配额不足"),
    JOB_NOT_READY(3005, "任务未完成，无法获取结果"),
    JOB_ALREADY_TERMINAL(3006, "任务已是终态"),
    NEWAPI_UNREACHABLE(3007, "NewAPI 服务不可达"),
    NEWAPI_TASK_FAILED(3008, "NewAPI 视频任务失败"),
    NEWAPI_TASK_TIMEOUT(3009, "NewAPI 视频任务超时"),
    NEWAPI_VIDEO_URL_MISSING(3010, "NewAPI 响应中未找到视频 URL"),

    // Workflow (4xxx)
    WORKFLOW_NOT_FOUND(4001, "工作流不存在"),
    WORKFLOW_ACCESS_DENIED(4002, "无权访问此工作流"),

    // Billing (5xxx) — Phase 8
    BILLING_NOT_ENABLED(5001, "计费模块未启用");

    private final int code;
    private final String message;

    ErrorCode(int code, String message) {
        this.code = code;
        this.message = message;
    }
}