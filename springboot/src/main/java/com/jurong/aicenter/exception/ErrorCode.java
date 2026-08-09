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
    BILLING_NOT_ENABLED(5001, "计费模块未启用"),

    // Admin (6xxx) — Phase 9 模块
    ADMIN_OPERATION_DENIED(6001, "管理员操作被拒绝"),                     // 通用兜底
    ADMIN_CANNOT_CHANGE_OWN_ROLE(6002, "不能修改自己的角色"),              // 禁改自己
    ADMIN_CANNOT_DISABLE_SELF(6003, "不能禁用自己的账号"),                  // 禁禁自己
    GROUP_NAME_DUPLICATE(6004, "分组名称已存在"),
    GROUP_IS_DEFAULT_CANNOT_DELETE(6005, "默认分组不可删除"),
    GROUP_IS_DEFAULT_CANNOT_UNSET(6006, "默认分组的 is_default 不可关闭"),
    USER_ALREADY_IN_GROUP(6007, "用户已在该分组中"),
    USER_NOT_IN_GROUP(6008, "用户不在该分组中"),
    INVALID_ROLE_VALUE(6009, "角色取值必须是 USER 或 ADMIN"),

    // Media (7xxx) — 资产库 / 素材
    MEDIA_LIBRARY_NAME_DUPLICATE(7001, "资产库名称已存在"),
    MEDIA_LIBRARY_NOT_FOUND(7002, "资产库不存在"),
    MEDIA_LIBRARY_IS_SYSTEM_CANNOT_MODIFY(7003, "系统默认资产库不可修改或删除"),
    MEDIA_ASSET_NOT_FOUND(7010, "素材不存在"),
    MEDIA_ASSET_TYPE_INVALID(7011, "素材类型不支持"),
    MEDIA_ASSET_NAME_DUPLICATE(7012, "当前库内已存在同名素材"),
    MEDIA_UPLOAD_FAILED(7020, "文件上传失败"),
    MEDIA_FILE_TOO_LARGE(7021, "文件超过大小限制"),
    MEDIA_FILE_EMPTY(7022, "文件为空"),
    // Aicoming 外部资产服务
    ASSET_UPLOAD_FAILED(7030, "外部资产上传失败"),
    ASSET_NOT_ACTIVE(7031, "外部资产未就绪"),
    ASSET_DELETE_FAILED(7032, "外部资产删除失败");

    private final int code;
    private final String message;

    ErrorCode(int code, String message) {
        this.code = code;
        this.message = message;
    }
}